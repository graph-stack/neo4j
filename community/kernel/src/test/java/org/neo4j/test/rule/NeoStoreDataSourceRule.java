/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.test.rule;

import java.io.File;
import java.util.Collections;
import java.util.function.Function;

import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.internal.diagnostics.DiagnosticsManager;
import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector;
import org.neo4j.internal.kernel.api.TokenNameLookup;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContextSupplier;
import org.neo4j.kernel.AvailabilityGuard;
import org.neo4j.kernel.DatabaseCreationContext;
import org.neo4j.kernel.NeoStoreDataSource;
import org.neo4j.kernel.api.explicitindex.AutoIndexing;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.impl.api.CommitProcessFactory;
import org.neo4j.kernel.impl.api.ExplicitIndexProvider;
import org.neo4j.kernel.impl.api.SchemaWriteGuard;
import org.neo4j.kernel.impl.api.explicitindex.InternalAutoIndexing;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.constraints.ConstraintSemantics;
import org.neo4j.kernel.impl.constraints.StandardConstraintSemantics;
import org.neo4j.kernel.impl.context.TransactionVersionContextSupplier;
import org.neo4j.kernel.impl.core.DatabasePanicEventGenerator;
import org.neo4j.kernel.impl.core.TokenHolders;
import org.neo4j.kernel.impl.factory.AccessCapability;
import org.neo4j.kernel.impl.factory.CanWrite;
import org.neo4j.kernel.impl.factory.CommunityCommitProcessFactory;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.index.IndexConfigStore;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.StatementLocks;
import org.neo4j.kernel.impl.locking.StatementLocksFactory;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.kernel.impl.logging.SimpleLogService;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.kernel.impl.query.QueryEngineProvider;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.id.BufferedIdController;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.id.IdController;
import org.neo4j.kernel.impl.store.id.BufferingIdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.DefaultIdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.IdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.IdReuseEligibility;
import org.neo4j.kernel.impl.store.id.configuration.CommunityIdTypeConfigurationProvider;
import org.neo4j.kernel.impl.store.id.configuration.IdTypeConfigurationProvider;
import org.neo4j.kernel.impl.transaction.TransactionHeaderInformationFactory;
import org.neo4j.kernel.impl.transaction.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.log.checkpoint.StoreCopyCheckPointMutex;
import org.neo4j.kernel.impl.transaction.log.files.LogFileCreationMonitor;
import org.neo4j.kernel.impl.transaction.stats.DatabaseTransactionStats;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.kernel.impl.util.UnsatisfiedDependencyException;
import org.neo4j.kernel.impl.util.collection.CollectionsFactorySupplier;
import org.neo4j.kernel.impl.util.watcher.FileSystemWatcherService;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.internal.TransactionEventHandlers;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.kernel.monitoring.tracing.Tracers;
import org.neo4j.logging.NullLog;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.time.Clocks;
import org.neo4j.time.SystemNanoClock;

import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.neo4j.kernel.impl.util.collection.CollectionsFactorySupplier.ON_HEAP;
import static org.neo4j.kernel.impl.util.watcher.FileSystemWatcherService.EMPTY_WATCHER;
import static org.neo4j.test.MockedNeoStores.mockedTokenHolders;

public class NeoStoreDataSourceRule extends ExternalResource
{
    private NeoStoreDataSource dataSource;

    public NeoStoreDataSource getDataSource( File storeDir, FileSystemAbstraction fs, PageCache pageCache )
    {
        return getDataSource( storeDir, fs, pageCache, new Dependencies() );
    }

    public NeoStoreDataSource getDataSource( File storeDir, FileSystemAbstraction fs, PageCache pageCache,
            DependencyResolver otherCustomOverriddenDependencies )
    {
        shutdownAnyRunning();

        StatementLocksFactory locksFactory = mock( StatementLocksFactory.class );
        StatementLocks statementLocks = mock( StatementLocks.class );
        Locks.Client locks = mock( Locks.Client.class );
        when( statementLocks.optimistic() ).thenReturn( locks );
        when( statementLocks.pessimistic() ).thenReturn( locks );
        when( locksFactory.newInstance() ).thenReturn( statementLocks );

        JobScheduler jobScheduler = mock( JobScheduler.class, RETURNS_MOCKS );
        Monitors monitors = new Monitors();

        Dependencies mutableDependencies = new Dependencies( otherCustomOverriddenDependencies );

        // Satisfy non-satisfied dependencies
        Config config = dependency( mutableDependencies, Config.class, deps -> Config.defaults() );
        LogService logService = dependency( mutableDependencies, LogService.class,
                deps -> new SimpleLogService( NullLogProvider.getInstance() ) );
        IdGeneratorFactory idGeneratorFactory = dependency( mutableDependencies, IdGeneratorFactory.class,
                deps -> new DefaultIdGeneratorFactory( fs ) );
        IdTypeConfigurationProvider idConfigurationProvider = dependency( mutableDependencies,
                IdTypeConfigurationProvider.class, deps -> new CommunityIdTypeConfigurationProvider() );
        DatabaseHealth databaseHealth = dependency( mutableDependencies, DatabaseHealth.class,
                deps -> new DatabaseHealth( mock( DatabasePanicEventGenerator.class ), NullLog.getInstance() ) );
        SystemNanoClock clock = dependency( mutableDependencies, SystemNanoClock.class, deps -> Clocks.nanoClock() );
        TransactionMonitor transactionMonitor = dependency( mutableDependencies, TransactionMonitor.class,
                deps -> new DatabaseTransactionStats() );
        AvailabilityGuard availabilityGuard = dependency( mutableDependencies, AvailabilityGuard.class,
                deps -> new AvailabilityGuard( deps.resolveDependency( SystemNanoClock.class ), NullLog.getInstance() ) );
        dependency( mutableDependencies, DiagnosticsManager.class,
                deps -> new DiagnosticsManager( NullLog.getInstance() ) );
        dependency( mutableDependencies, IndexProvider.class, deps -> IndexProvider.EMPTY );

        dataSource = new NeoStoreDataSource(
                new TestDatabaseCreationContext( DatabaseManager.DEFAULT_DATABASE_NAME, storeDir, config, idGeneratorFactory, logService,
                        mock( JobScheduler.class, RETURNS_MOCKS ), mock( TokenNameLookup.class ), mutableDependencies, mockedTokenHolders(), locksFactory,
                        mock( SchemaWriteGuard.class ), mock( TransactionEventHandlers.class ), IndexingService.NO_MONITOR, fs, transactionMonitor,
                        databaseHealth, mock( LogFileCreationMonitor.class ), TransactionHeaderInformationFactory.DEFAULT, new CommunityCommitProcessFactory(),
                        mock( InternalAutoIndexing.class ), mock( IndexConfigStore.class ), mock( ExplicitIndexProvider.class ), pageCache,
                        new StandardConstraintSemantics(), monitors, new Tracers( "null", NullLog.getInstance(), monitors, jobScheduler, clock ),
                        mock( Procedures.class ), IOLimiter.UNLIMITED, availabilityGuard, clock, new CanWrite(), new StoreCopyCheckPointMutex(),
                        RecoveryCleanupWorkCollector.IMMEDIATE,
                        new BufferedIdController( new BufferingIdGeneratorFactory( idGeneratorFactory, IdReuseEligibility.ALWAYS, idConfigurationProvider ),
                                jobScheduler ), DatabaseInfo.COMMUNITY, new TransactionVersionContextSupplier(), ON_HEAP, Collections.emptyList(),
                        file -> EMPTY_WATCHER, new GraphDatabaseFacade(), Iterables.empty() ) );
        return dataSource;
    }

    private static <T> T dependency( Dependencies dependencies, Class<T> type, Function<DependencyResolver,T> defaultSupplier )
    {
        try
        {
            return dependencies.resolveDependency( type );
        }
        catch ( IllegalArgumentException | UnsatisfiedDependencyException e )
        {
            return dependencies.satisfyDependency( defaultSupplier.apply( dependencies ) );
        }
    }

    private void shutdownAnyRunning()
    {
        if ( dataSource != null )
        {
            dataSource.stop();
        }
    }

    @Override
    protected void after( boolean successful )
    {
        shutdownAnyRunning();
    }

    private static class TestDatabaseCreationContext implements DatabaseCreationContext
    {
        private final String databaseName;
        private final File databaseDirectory;
        private final Config config;
        private final IdGeneratorFactory idGeneratorFactory;
        private final LogService logService;
        private final JobScheduler scheduler;
        private final TokenNameLookup tokenNameLookup;
        private final DependencyResolver dependencyResolver;
        private final TokenHolders tokenHolders;
        private final StatementLocksFactory statementLocksFactory;
        private final SchemaWriteGuard schemaWriteGuard;
        private final TransactionEventHandlers transactionEventHandlers;
        private final IndexingService.Monitor indexingServiceMonitor;
        private final FileSystemAbstraction fs;
        private final TransactionMonitor transactionMonitor;
        private final DatabaseHealth databaseHealth;
        private final LogFileCreationMonitor physicalLogMonitor;
        private final TransactionHeaderInformationFactory transactionHeaderInformationFactory;
        private final CommitProcessFactory commitProcessFactory;
        private final AutoIndexing autoIndexing;
        private final IndexConfigStore indexConfigStore;
        private final ExplicitIndexProvider explicitIndexProvider;
        private final PageCache pageCache;
        private final ConstraintSemantics constraintSemantics;
        private final Monitors monitors;
        private final Tracers tracers;
        private final Procedures procedures;
        private final IOLimiter ioLimiter;
        private final AvailabilityGuard availabilityGuard;
        private final SystemNanoClock clock;
        private final AccessCapability accessCapability;
        private final StoreCopyCheckPointMutex storeCopyCheckPointMutex;
        private final RecoveryCleanupWorkCollector recoveryCleanupWorkCollector;
        private final IdController idController;
        private final DatabaseInfo databaseInfo;
        private final VersionContextSupplier versionContextSupplier;
        private final CollectionsFactorySupplier collectionsFactorySupplier;
        private final Iterable<KernelExtensionFactory<?>> kernelExtensionFactories;
        private final Function<File,FileSystemWatcherService> watcherServiceFactory;
        private final GraphDatabaseFacade facade;
        private final Iterable<QueryEngineProvider> engineProviders;

        TestDatabaseCreationContext( String databaseName, File databaseDirectory, Config config, IdGeneratorFactory idGeneratorFactory, LogService logService,
                JobScheduler scheduler, TokenNameLookup tokenNameLookup, DependencyResolver dependencyResolver, TokenHolders tokenHolders,
                StatementLocksFactory statementLocksFactory, SchemaWriteGuard schemaWriteGuard, TransactionEventHandlers transactionEventHandlers,
                IndexingService.Monitor indexingServiceMonitor, FileSystemAbstraction fs, TransactionMonitor transactionMonitor, DatabaseHealth databaseHealth,
                LogFileCreationMonitor physicalLogMonitor, TransactionHeaderInformationFactory transactionHeaderInformationFactory,
                CommitProcessFactory commitProcessFactory, AutoIndexing autoIndexing, IndexConfigStore indexConfigStore,
                ExplicitIndexProvider explicitIndexProvider, PageCache pageCache, ConstraintSemantics constraintSemantics, Monitors monitors, Tracers tracers,
                Procedures procedures, IOLimiter ioLimiter, AvailabilityGuard availabilityGuard, SystemNanoClock clock, AccessCapability accessCapability,
                StoreCopyCheckPointMutex storeCopyCheckPointMutex, RecoveryCleanupWorkCollector recoveryCleanupWorkCollector, IdController idController,
                DatabaseInfo databaseInfo, VersionContextSupplier versionContextSupplier, CollectionsFactorySupplier collectionsFactorySupplier,
                Iterable<KernelExtensionFactory<?>> kernelExtensionFactories, Function<File,FileSystemWatcherService> watcherServiceFactory,
                GraphDatabaseFacade facade, Iterable<QueryEngineProvider> engineProviders )
        {
            this.databaseName = databaseName;
            this.databaseDirectory = databaseDirectory;
            this.config = config;
            this.idGeneratorFactory = idGeneratorFactory;
            this.logService = logService;
            this.scheduler = scheduler;
            this.tokenNameLookup = tokenNameLookup;
            this.dependencyResolver = dependencyResolver;
            this.tokenHolders = tokenHolders;
            this.statementLocksFactory = statementLocksFactory;
            this.schemaWriteGuard = schemaWriteGuard;
            this.transactionEventHandlers = transactionEventHandlers;
            this.indexingServiceMonitor = indexingServiceMonitor;
            this.fs = fs;
            this.transactionMonitor = transactionMonitor;
            this.databaseHealth = databaseHealth;
            this.physicalLogMonitor = physicalLogMonitor;
            this.transactionHeaderInformationFactory = transactionHeaderInformationFactory;
            this.commitProcessFactory = commitProcessFactory;
            this.autoIndexing = autoIndexing;
            this.indexConfigStore = indexConfigStore;
            this.explicitIndexProvider = explicitIndexProvider;
            this.pageCache = pageCache;
            this.constraintSemantics = constraintSemantics;
            this.monitors = monitors;
            this.tracers = tracers;
            this.procedures = procedures;
            this.ioLimiter = ioLimiter;
            this.availabilityGuard = availabilityGuard;
            this.clock = clock;
            this.accessCapability = accessCapability;
            this.storeCopyCheckPointMutex = storeCopyCheckPointMutex;
            this.recoveryCleanupWorkCollector = recoveryCleanupWorkCollector;
            this.idController = idController;
            this.databaseInfo = databaseInfo;
            this.versionContextSupplier = versionContextSupplier;
            this.collectionsFactorySupplier = collectionsFactorySupplier;
            this.kernelExtensionFactories = kernelExtensionFactories;
            this.watcherServiceFactory = watcherServiceFactory;
            this.facade = facade;
            this.engineProviders = engineProviders;
        }

        @Override
        public String getDatabaseName()
        {
            return databaseName;
        }

        public File getDatabaseDirectory()
        {
            return databaseDirectory;
        }

        public Config getConfig()
        {
            return config;
        }

        public IdGeneratorFactory getIdGeneratorFactory()
        {
            return idGeneratorFactory;
        }

        public LogService getLogService()
        {
            return logService;
        }

        public JobScheduler getScheduler()
        {
            return scheduler;
        }

        public TokenNameLookup getTokenNameLookup()
        {
            return tokenNameLookup;
        }

        @Override
        public DependencyResolver getGlobalDependencies()
        {
            return dependencyResolver;
        }

        public DependencyResolver getDependencyResolver()
        {
            return dependencyResolver;
        }

        public TokenHolders getTokenHolders()
        {
            return tokenHolders;
        }

        public StatementLocksFactory getStatementLocksFactory()
        {
            return statementLocksFactory;
        }

        public SchemaWriteGuard getSchemaWriteGuard()
        {
            return schemaWriteGuard;
        }

        public TransactionEventHandlers getTransactionEventHandlers()
        {
            return transactionEventHandlers;
        }

        public IndexingService.Monitor getIndexingServiceMonitor()
        {
            return indexingServiceMonitor;
        }

        public FileSystemAbstraction getFs()
        {
            return fs;
        }

        public TransactionMonitor getTransactionMonitor()
        {
            return transactionMonitor;
        }

        public DatabaseHealth getDatabaseHealth()
        {
            return databaseHealth;
        }

        public LogFileCreationMonitor getPhysicalLogMonitor()
        {
            return physicalLogMonitor;
        }

        public TransactionHeaderInformationFactory getTransactionHeaderInformationFactory()
        {
            return transactionHeaderInformationFactory;
        }

        public CommitProcessFactory getCommitProcessFactory()
        {
            return commitProcessFactory;
        }

        public AutoIndexing getAutoIndexing()
        {
            return autoIndexing;
        }

        public IndexConfigStore getIndexConfigStore()
        {
            return indexConfigStore;
        }

        public ExplicitIndexProvider getExplicitIndexProvider()
        {
            return explicitIndexProvider;
        }

        public PageCache getPageCache()
        {
            return pageCache;
        }

        public ConstraintSemantics getConstraintSemantics()
        {
            return constraintSemantics;
        }

        public Monitors getMonitors()
        {
            return monitors;
        }

        public Tracers getTracers()
        {
            return tracers;
        }

        public Procedures getProcedures()
        {
            return procedures;
        }

        public IOLimiter getIoLimiter()
        {
            return ioLimiter;
        }

        public AvailabilityGuard getAvailabilityGuard()
        {
            return availabilityGuard;
        }

        public SystemNanoClock getClock()
        {
            return clock;
        }

        public AccessCapability getAccessCapability()
        {
            return accessCapability;
        }

        public StoreCopyCheckPointMutex getStoreCopyCheckPointMutex()
        {
            return storeCopyCheckPointMutex;
        }

        public RecoveryCleanupWorkCollector getRecoveryCleanupWorkCollector()
        {
            return recoveryCleanupWorkCollector;
        }

        public IdController getIdController()
        {
            return idController;
        }

        public DatabaseInfo getDatabaseInfo()
        {
            return databaseInfo;
        }

        public VersionContextSupplier getVersionContextSupplier()
        {
            return versionContextSupplier;
        }

        public CollectionsFactorySupplier getCollectionsFactorySupplier()
        {
            return collectionsFactorySupplier;
        }

        public Iterable<KernelExtensionFactory<?>> getKernelExtensionFactories()
        {
            return kernelExtensionFactories;
        }

        public Function<File,FileSystemWatcherService> getWatcherServiceFactory()
        {
            return watcherServiceFactory;
        }

        public GraphDatabaseFacade getFacade()
        {
            return facade;
        }

        public Iterable<QueryEngineProvider> getEngineProviders()
        {
            return engineProviders;
        }
    }

}
