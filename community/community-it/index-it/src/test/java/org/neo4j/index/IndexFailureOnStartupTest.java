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
package org.neo4j.index;

import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.index.IndexDirectoryStructure;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.EmbeddedDatabaseRule;
import org.neo4j.values.storable.CoordinateReferenceSystem;
import org.neo4j.values.storable.Values;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.neo4j.graphdb.schema.Schema.IndexState.ONLINE;
import static org.neo4j.kernel.api.impl.schema.LuceneIndexProviderFactory.PROVIDER_DESCRIPTOR;
import static org.neo4j.kernel.api.impl.schema.NativeLuceneFusionIndexProviderFactory20.subProviderDirectoryStructure;

public class IndexFailureOnStartupTest
{
    private static final Label PERSON = Label.label( "Person" );
    @Rule
    public final DatabaseRule db = new EmbeddedDatabaseRule().startLazily();

    @Test
    public void failedIndexShouldRepairAutomatically() throws Exception
    {
        // given
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().indexFor( PERSON ).on( "name" ).create();
            tx.success();
        }
        awaitIndexesOnline( 5, SECONDS );
        createNamed( PERSON, "Johan" );
        // when - we restart the database in a state where the index is not operational
        db.restartDatabase( new DeleteIndexFile( "_0.cfs" ) );
        // then - the database should still be operational
        createNamed( PERSON, "Lars" );
        awaitIndexesOnline( 5, SECONDS );
        indexStateShouldBe( equalTo( ONLINE ) );
        assertFindsNamed( PERSON, "Lars" );
    }

    @Test
    public void shouldNotBeAbleToViolateConstraintWhenBackingIndexFailsToOpen() throws Exception
    {
        // given
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().constraintFor( PERSON ).assertPropertyIsUnique( "name" ).create();
            tx.success();
        }
        createNamed( PERSON, "Lars" );
        // when - we restart the database in a state where the index is not operational
        db.restartDatabase( new DeleteIndexFile( "_0.cfs" ) );
        // then - we must not be able to violate the constraint
        createNamed( PERSON, "Johan" );
        Throwable failure = null;
        try
        {
            createNamed( PERSON, "Lars" );
        }
        catch ( Throwable e )
        {
            // this must fail, otherwise we have violated the constraint
            failure = e;
        }
        assertNotNull( failure );
        indexStateShouldBe( equalTo( ONLINE ) );
    }

    @Test
    public void shouldArchiveFailedIndex() throws Exception
    {
        // given
        db.setConfig( GraphDatabaseSettings.archive_failed_index, "true" );
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode( PERSON );
            node.setProperty( "name", "Fry" );
            tx.success();
        }
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode( PERSON );
            node.setProperty( "name", Values.pointValue( CoordinateReferenceSystem.WGS84, 1, 2 ) );
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            db.schema().constraintFor( PERSON ).assertPropertyIsUnique( "name" ).create();
            tx.success();
        }
        assertThat( archiveFile(), nullValue() );

        // when
        db.restartDatabase( new DeleteIndexFile( "segments_" ) );

        // then
        indexStateShouldBe( equalTo( ONLINE ) );
        assertThat( archiveFile(), notNullValue() );
    }

    private File archiveFile() throws IOException
    {
        try ( FileSystemAbstraction fs = new DefaultFileSystemAbstraction() )
        {
            File indexDir = indexRootDirectory( db.databaseDirectory() );
            File[] files = indexDir.listFiles( pathname -> pathname.isFile() && pathname.getName().startsWith( "archive-" ) );
            if ( files == null || files.length == 0 )
            {
                return null;
            }
            assertEquals( 1, files.length );
            return files[0];
        }
    }

    private void awaitIndexesOnline( int timeout, TimeUnit unit )
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( timeout, unit );
            tx.success();
        }
    }

    private void assertFindsNamed( Label label, String name )
    {
        try ( Transaction tx = db.beginTx() )
        {
            assertNotNull( "Must be able to find node created while index was offline",
                    db.findNode( label, "name", name ) );
            tx.success();
        }
    }

    private void indexStateShouldBe( Matcher<Schema.IndexState> matchesExpectation )
    {
        try ( Transaction tx = db.beginTx() )
        {
            for ( IndexDefinition index : db.schema().getIndexes() )
            {
                assertThat( db.schema().getIndexState( index ), matchesExpectation );
            }
            tx.success();
        }
    }

    private void createNamed( Label label, String name )
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.createNode( label ).setProperty( "name", name );
            tx.success();
        }
    }

    private static class DeleteIndexFile implements DatabaseRule.RestartAction
    {
        private final String prefix;

        DeleteIndexFile( String prefix )
        {
            this.prefix = prefix;
        }

        @Override
        public void run( FileSystemAbstraction fs, File base )
        {
            File indexRootDirectory = new File( soleIndexDir( base ), "1" /*the partition*/ );
            File[] files = fs.listFiles( indexRootDirectory, ( dir, name ) -> name.startsWith( prefix ) );
            Stream.of( files ).forEach( fs::deleteFile );
        }
    }

    private static File indexRootDirectory( File base )
    {
        return providerDirectoryStructure( base ).rootDirectory();
    }

    private static File soleIndexDir( File base )
    {
        return providerDirectoryStructure( base ).directoryForIndex( 1 );
    }

    private static IndexDirectoryStructure providerDirectoryStructure( File base )
    {
        return subProviderDirectoryStructure( base ).forProvider( PROVIDER_DESCRIPTOR );
    }
}
