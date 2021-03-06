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
package org.neo4j.kernel.impl.store;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.facade.GraphDatabaseFacadeFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.factory.module.CommunityEditionModule;
import org.neo4j.graphdb.factory.module.PlatformModule;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.tracing.cursor.context.EmptyVersionContextSupplier;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.configuration.Settings;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.store.id.DefaultIdGeneratorFactory;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.ImpermanentGraphDatabase;
import org.neo4j.test.rule.PageCacheRule;
import org.neo4j.test.rule.TestDirectory;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class IdGeneratorRebuildFailureEmulationTest
{
    private FileSystem fs;
    private StoreFactory factory;
    private File storeDirectory;

    @Rule
    public final TestDirectory testDirectory = TestDirectory.testDirectory();
    @Rule
    public final PageCacheRule pageCacheRule = new PageCacheRule();

    @Before
    public void initialize()
    {
        fs = new FileSystem();
        storeDirectory = testDirectory.storeDir();
        GraphDatabaseService graphdb = new Database( storeDirectory );
        createInitialData( graphdb );
        graphdb.shutdown();
        Map<String, String> params = new HashMap<>();
        params.put( GraphDatabaseSettings.rebuild_idgenerators_fast.name(), Settings.FALSE );
        Config config = Config.defaults( params );
        factory = new StoreFactory( DatabaseManager.DEFAULT_DATABASE_NAME, testDirectory.databaseDir(), config, new DefaultIdGeneratorFactory( fs ),
                pageCacheRule.getPageCache( fs ), fs, NullLogProvider.getInstance(), EmptyVersionContextSupplier.EMPTY );
    }

    @After
    public void verifyAndDispose() throws Exception
    {
        GraphDatabaseService graphdb = null;
        try
        {
            graphdb = new Database( storeDirectory );
            verifyData( graphdb );
        }
        finally
        {
            if ( graphdb != null )
            {
                graphdb.shutdown();
            }
            if ( fs != null )
            {
                fs.disposeAndAssertNoOpenFiles();
            }
            fs = null;
        }
    }

    private void performTest( String neostoreFileName )
    {
        File idFile = new File( testDirectory.databaseDir(), neostoreFileName + ".id" );
        // emulate the need for rebuilding id generators by deleting it
        fs.deleteFile( idFile );
        try ( NeoStores neoStores = factory.openAllNeoStores() )
        {
            // emulate a failure during rebuild:
        }
        catch ( UnderlyingStorageException expected )
        {
            assertThat( expected.getMessage(), startsWith( "Id capacity exceeded" ) );
        }
    }

    private void verifyData( GraphDatabaseService graphdb )
    {
        try ( Transaction tx = graphdb.beginTx() )
        {
            int nodecount = 0;
            for ( Node node : graphdb.getAllNodes() )
            {
                int propcount = readProperties( node );
                int relcount = 0;
                for ( Relationship rel : node.getRelationships() )
                {
                    assertEquals( "all relationships should have 3 properties.", 3, readProperties( rel ) );
                    relcount++;
                }
                assertEquals( "all created nodes should have 3 properties.", 3, propcount );
                assertEquals( "all created nodes should have 2 relationships.", 2, relcount );

                nodecount++;
            }
            assertEquals( "The database should have 2 nodes.", 2, nodecount );
        }
    }

    private void createInitialData( GraphDatabaseService graphdb )
    {
        try ( Transaction tx = graphdb.beginTx() )
        {
            Node first = properties( graphdb.createNode() );
            Node other = properties( graphdb.createNode() );
            properties( first.createRelationshipTo( other, RelationshipType.withName( "KNOWS" ) ) );
            properties( other.createRelationshipTo( first, RelationshipType.withName( "DISTRUSTS" ) ) );

            tx.success();
        }
    }

    private <E extends PropertyContainer> E properties( E entity )
    {
        entity.setProperty( "short thing", "short" );
        entity.setProperty( "long thing",
                "this is quite a long string, don't you think, it sure is long enough at least" );
        entity.setProperty( "string array", new String[]{"these are a few", "cool strings",
                "for your viewing pleasure"} );
        return entity;
    }

    private int readProperties( PropertyContainer entity )
    {
        int count = 0;
        for ( String key : entity.getPropertyKeys() )
        {
            entity.getProperty( key );
            count++;
        }
        return count;
    }

    private static class FileSystem extends EphemeralFileSystemAbstraction
    {
        void disposeAndAssertNoOpenFiles() throws Exception
        {
            assertNoOpenFiles();
            super.close();
        }

        @Override
        public void close()
        {
        }
    }

    @SuppressWarnings( "deprecation" )
    private class Database extends ImpermanentGraphDatabase
    {
        Database( File storeDir )
        {
            super( storeDir );
        }

        @Override
        protected void create( File storeDir, Map<String, String> params, GraphDatabaseFacadeFactory.Dependencies dependencies )
        {
            new GraphDatabaseFacadeFactory( DatabaseInfo.COMMUNITY, CommunityEditionModule::new )
            {
                @Override
                protected PlatformModule createPlatform( File storeDir, Config config, Dependencies dependencies )
                {
                    return new ImpermanentPlatformModule( storeDir, config, databaseInfo, dependencies )
                    {
                        @Override
                        protected FileSystemAbstraction createFileSystemAbstraction()
                        {
                            return fs;
                        }
                    };
                }
            }.initFacade( storeDir, params, dependencies, this );
        }

    }

    @Test
    public void neostore()
    {
        performTest( MetaDataStore.DEFAULT_NAME );
    }

    @Test
    public void neostore_nodestore_db()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.NODE_STORE_NAME );
    }

    @Test
    public void neostore_propertystore_db_arrays()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.PROPERTY_ARRAYS_STORE_NAME );
    }

    @Test
    public void neostore_propertystore_db()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.PROPERTY_STORE_NAME );
    }

    @Test
    public void neostore_propertystore_db_index()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.PROPERTY_KEY_TOKEN_STORE_NAME );
    }

    @Test
    public void neostore_propertystore_db_index_keys()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.PROPERTY_KEY_TOKEN_NAMES_STORE_NAME );
    }

    @Test
    public void neostore_propertystore_db_strings()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.PROPERTY_STRINGS_STORE_NAME );
    }

    @Test
    public void neostore_relationshipstore_db()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.RELATIONSHIP_STORE_NAME );
    }

    @Test
    public void neostore_relationshiptypestore_db()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.RELATIONSHIP_TYPE_TOKEN_STORE_NAME );
    }

    @Test
    public void neostore_relationshiptypestore_db_names()
    {
        performTest( MetaDataStore.DEFAULT_NAME + StoreFactory.RELATIONSHIP_TYPE_TOKEN_NAMES_STORE_NAME );
    }
}
