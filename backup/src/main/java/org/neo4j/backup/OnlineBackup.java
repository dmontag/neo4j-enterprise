/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import org.neo4j.com.MasterUtil;
import org.neo4j.com.MasterUtil.TxHandler;
import org.neo4j.com.Response;
import org.neo4j.com.SlaveContext;
import org.neo4j.com.ToFileStoreWriter;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.Pair;
import org.neo4j.helpers.Service;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.kernel.impl.transaction.XaDataSourceManager;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;
import org.neo4j.kernel.impl.util.StringLogger;

public class OnlineBackup
{
    private final URI hostURI;
    private final int port;
    private final Map<String, Long> lastCommittedTxs = new TreeMap<String, Long>();
    public static final String DEFAULT_SCHEME = "single";

    /**
     *
     * @param backupURI The URI string of the host to backup from, including the
     *            scheme as the backup URI resolver service, for example
     *            <i>ha</i> or <i>single</i>. Default to <i>single</i>.
     * @param port The port the host to backup from listens for requests. This
     *            parameter is overridden by the port in the URI if present.
     * @return An OnlineBackup object bound to the host specified in the
     *         backupURI, ready to serve backup requests.
     * @deprecated Use {@link OnlineBackup#from(String)} with the port as part
     *             of the URI
     */
    @Deprecated
    public static OnlineBackup from( String backupURI, int port )
    {
        return new OnlineBackup( backupURI, port );
    }

    /**
     * @param backupURI The URI string of the host to backup from, including the
     *            scheme as the backup URI resolver service, for example
     *            <i>ha</i> or <i>single</i>. Default to <i>single</i>.
     * @return An OnlineBackup object bound to the host specified in the
     *         backupURI, ready to serve backup requests.
     */
    public static OnlineBackup from( String backupURI )
    {
        return new OnlineBackup( backupURI, BackupServer.DEFAULT_PORT );
    }

    private OnlineBackup( String backupURI, int port )
    {
        this.hostURI = parseAndResolveURI( backupURI );
        this.port = hostURI.getPort() != -1 ? hostURI.getPort() : port;
    }

    private URI parseAndResolveURI( String backupURIString )
            throws IllegalArgumentException
    {
        URI backupURI = null;
        /*
         * The initial API was not supporting URIs, so we have this
         * hack for detecting lack of scheme (properly formatted URI)
         * and add it, otherwise the host will be null.
         */
        if ( backupURIString.indexOf( "://" ) == -1 )
        {
            backupURIString = DEFAULT_SCHEME + "://" + backupURIString;
        }
        try
        {
            backupURI = new URI( backupURIString );
        }
        catch ( URISyntaxException e )
        {
            throw new IllegalArgumentException( e );
        }

        String module = backupURI.getScheme();

        /*
         * So, the scheme is considered to be the module name and an attempt at
         * loading the service is made.
         */
        BackupExtensionService service = null;
        if ( module != null && !DEFAULT_SCHEME.equals( module ) )
        {
            try
            {
                service = Service.load( BackupExtensionService.class, module );
            }
            catch ( NoSuchElementException e )
            {
                throw new IllegalArgumentException(
                        String.format(
                        "%s was specified as a backup module but it was not found. Please make sure that the implementing service is on the classpath.",
                        module ) );
            }
        }
        if ( service != null )
        { // If in here, it means a module was loaded. Use it and substitute the
          // passed URI
            backupURI = service.resolve( backupURI );
        }

        return backupURI;
    }

    public OnlineBackup full( String targetDirectory )
    {
        if ( directoryContainsDb( targetDirectory ) )
        {
            throw new RuntimeException( targetDirectory + " already contains a database" );
        }

        //                                                     TODO OMG this is ugly
        BackupClient client = new BackupClient( hostURI.getHost(), port,
                new NotYetExistingGraphDatabase( targetDirectory ) );
        try
        {
            Response<Void> response = client.fullBackup( new ToFileStoreWriter( targetDirectory ) );
            GraphDatabaseService targetDb = startTemporaryDb( targetDirectory );
            try
            {
                unpackResponse( response, targetDb, MasterUtil.txHandlerForFullCopy() );
            }
            finally
            {
                targetDb.shutdown();
            }
        }
        finally
        {
            client.shutdown();
            // TODO This is also ugly
            StringLogger.close( targetDirectory );
        }
        return this;
    }

    private boolean directoryContainsDb( String targetDirectory )
    {
        return new File( targetDirectory, "neostore" ).exists();
    }

    public int getPort()
    {
        return port;
    }

    public String getHostNameOrIp()
    {
        return hostURI.getHost();
    }

    public Map<String, Long> getLastCommittedTxs()
    {
        return Collections.unmodifiableMap( lastCommittedTxs );
    }

    private EmbeddedGraphDatabase startTemporaryDb( String targetDirectory )
    {
        return new EmbeddedGraphDatabase( targetDirectory );
    }

    public OnlineBackup incremental( String targetDirectory )
    {
        if ( !directoryContainsDb( targetDirectory ) )
        {
            throw new RuntimeException( targetDirectory + " doesn't contain a database" );
        }

        GraphDatabaseService targetDb = startTemporaryDb( targetDirectory );
        try
        {
            return incremental( targetDb );
        }
        finally
        {
            targetDb.shutdown();
        }
    }

    public OnlineBackup incremental( GraphDatabaseService targetDb )
    {
        BackupClient client = new BackupClient( hostURI.getHost(), port,
                targetDb );
        try
        {
            unpackResponse( client.incrementalBackup( slaveContextOf( targetDb ) ), targetDb, MasterUtil.NO_ACTION );
        }
        finally
        {
            client.shutdown();
        }
        return this;
    }

    private void unpackResponse( Response<Void> response, GraphDatabaseService graphDb, TxHandler txHandler )
    {
        try
        {
            MasterUtil.applyReceivedTransactions( response, graphDb, txHandler );
            getLastCommittedTxs( graphDb );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Unable to apply received transactions", e );
        }
    }

    private void getLastCommittedTxs( GraphDatabaseService graphDb )
    {
        for ( XaDataSource ds : ((AbstractGraphDatabase) graphDb).getConfig().getTxModule().getXaDataSourceManager().getAllRegisteredDataSources() )
        {
            lastCommittedTxs.put( ds.getName(), ds.getLastCommittedTxId() );
        }
    }

    @SuppressWarnings( "unchecked" )
    private SlaveContext slaveContextOf( GraphDatabaseService graphDb )
    {
        XaDataSourceManager dsManager =
                ((AbstractGraphDatabase) graphDb).getConfig().getTxModule().getXaDataSourceManager();
        List<Pair<String, Long>> txs = new ArrayList<Pair<String,Long>>();
        for ( XaDataSource ds : dsManager.getAllRegisteredDataSources() )
        {
            txs.add( Pair.of( ds.getName(), ds.getLastCommittedTxId() ) );
        }
        return SlaveContext.anonymous( txs.toArray( new Pair[0] ) );
    }
}
