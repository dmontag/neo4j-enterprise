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
package org.neo4j.kernel;

import org.neo4j.com.ComException;
import org.neo4j.com.MasterUtil;
import org.neo4j.com.Response;
import org.neo4j.com.SlaveContext;
import org.neo4j.com.ToFileStoreWriter;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.ErrorState;
import org.neo4j.graphdb.event.KernelEventHandler;
import org.neo4j.graphdb.event.TransactionEventHandler;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.ha.BranchedDataException;
import org.neo4j.kernel.ha.Broker;
import org.neo4j.kernel.ha.BrokerFactory;
import org.neo4j.kernel.ha.HaConfig;
import org.neo4j.kernel.ha.Master;
import org.neo4j.kernel.ha.MasterIdGeneratorFactory;
import org.neo4j.kernel.ha.MasterServer;
import org.neo4j.kernel.ha.MasterTxIdGenerator.MasterTxIdGeneratorFactory;
import org.neo4j.kernel.ha.ResponseReceiver;
import org.neo4j.kernel.ha.SlaveIdGenerator.SlaveIdGeneratorFactory;
import org.neo4j.kernel.ha.SlaveLockManager.SlaveLockManagerFactory;
import org.neo4j.kernel.ha.SlaveRelationshipTypeCreator;
import org.neo4j.kernel.ha.SlaveTxFinishHook;
import org.neo4j.kernel.ha.SlaveTxIdGenerator.SlaveTxIdGeneratorFactory;
import org.neo4j.kernel.ha.SlaveUpdateMode;
import org.neo4j.kernel.ha.TimeUtil;
import org.neo4j.kernel.ha.ZooKeeperLastCommittedTxIdSetter;
import org.neo4j.kernel.ha.zookeeper.Machine;
import org.neo4j.kernel.ha.zookeeper.ZooKeeperBroker;
import org.neo4j.kernel.ha.zookeeper.ZooKeeperException;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.transaction.XaDataSourceManager;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;
import org.neo4j.kernel.impl.transaction.xaframework.XaLogicalLog;
import org.neo4j.kernel.impl.util.FileUtils;
import org.neo4j.kernel.impl.util.StringLogger;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.lang.Math.max;
import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.kernel.Config.KEEP_LOGICAL_LOGS;
import static org.neo4j.kernel.impl.nioneo.xa.NeoStoreXaDataSource.LOGICAL_LOG_DEFAULT_NAME;
import static org.neo4j.kernel.impl.transaction.xaframework.XaLogicalLog.getHistoryFileNamePattern;
import static org.neo4j.kernel.impl.transaction.xaframework.XaLogicalLog.getHistoryLogVersion;

public class HighlyAvailableGraphDatabase extends AbstractGraphDatabase
        implements GraphDatabaseService, ResponseReceiver
{
    private final String storeDir;
    private final Map<String, String> config;
    private final BrokerFactory brokerFactory;
    private final Broker broker;
    private volatile EmbeddedGraphDbImpl localGraph;
    private final int machineId;
    private volatile MasterServer masterServer;
    private ScheduledExecutorService updatePuller;
    private volatile long updateTime = 0;
    private volatile Throwable causeOfShutdown;
    private final long startupTime;
    private final BranchedDataPolicy branchedDataPolicy;
    private final SlaveUpdateMode slaveUpdateMode;

    private final List<KernelEventHandler> kernelEventHandlers =
            new CopyOnWriteArrayList<KernelEventHandler>();
    private final Collection<TransactionEventHandler<?>> transactionEventHandlers =
            new CopyOnWriteArraySet<TransactionEventHandler<?>>();

    private final StringLogger msgLog;

    /**
     * Will instantiate its own ZooKeeper broker
     */
    public HighlyAvailableGraphDatabase( String storeDir, Map<String, String> config )
    {
        this( storeDir, config, null );
    }

    /**
     * Only for testing (and {@link org.neo4j.kernel.ha.BackupFromHaCluster})
     */
    public HighlyAvailableGraphDatabase( String storeDir, Map<String, String> config,
            BrokerFactory brokerFactory )
    {
        if ( config == null )
        {
            throw new IllegalArgumentException( "null config, proper configuration required" );
        }
        initializeTxManagerKernelPanicEventHandler();
        this.startupTime = System.currentTimeMillis();
        this.storeDir = storeDir;
        this.config = config;
        config.put( Config.KEEP_LOGICAL_LOGS, "true" );
        this.slaveUpdateMode = HaConfig.getSlaveUpdateModeFromConfig( config );
        this.brokerFactory = brokerFactory != null ? brokerFactory : defaultBrokerFactory(
                this, config );
        this.machineId = HaConfig.getMachineIdFromConfig( config );
        this.broker = this.brokerFactory.create( this, config );
        this.msgLog = StringLogger.getLogger( storeDir );
        this.branchedDataPolicy = getBranchedDataPolicyFromConfig( config );

        boolean allowInitFromConfig = getAllowInitFromConfig( config );
        startUp( allowInitFromConfig );
    }

    private void initializeTxManagerKernelPanicEventHandler()
    {
        kernelEventHandlers.add( new KernelEventHandler()
        {
            @Override public void beforeShutdown() {}

            @Override
            public void kernelPanic( ErrorState error )
            {
                if ( error == ErrorState.TX_MANAGER_NOT_OK )
                {
                    msgLog.logMessage( "TxManager not ok, doing internal restart" );
                    internalShutdown( true );
                    newMaster( null, new Exception( "Tx manager not ok" ) );
                }
            }

            @Override
            public Object getResource()
            {
                return null;
            }

            @Override
            public ExecutionOrder orderComparedTo( KernelEventHandler other )
            {
                return ExecutionOrder.DOESNT_MATTER;
            }
        } );
    }

    private void getFreshDatabaseFromMaster( Pair<Master, Machine> master )
    {
        master = master != null ? master : broker.getMasterReally();
        // Assume it's shut down at this point

        internalShutdown( false );
        makeWayForNewDb();

        Exception exception = null;
        for ( int i = 0; i < 60; i++ )
        {
            try
            {
                copyStoreFromMaster( master );
                return;
            }
            // TODO Maybe catch IOException and treat it more seriously?
            catch ( Exception e )
            {
                msgLog.logMessage( "Problems copying store from master", e );
                sleepWithoutInterruption( 1000, "" );
                exception = e;
                master = broker.getMasterReally();
            }
        }
        throw new RuntimeException( "Gave up trying to copy store from master", exception );
    }

    void makeWayForNewDb()
    {
        this.msgLog.logMessage( "Cleaning database " + storeDir + " (" + branchedDataPolicy.name() +
                ") to make way for new db from master" );
        branchedDataPolicy.handle( this );
    }

    public static Map<String,String> loadConfigurations( String file )
    {
        return EmbeddedGraphDatabase.loadConfigurations( file );
    }

    private synchronized void startUp( boolean allowInit )
    {
        StoreId storeId = null;
        if ( !new File( storeDir, NeoStore.DEFAULT_NAME ).exists() )
        {   // Try for 
            long endTime = System.currentTimeMillis()+60000;
            Exception exception = null;
            while ( System.currentTimeMillis() < endTime )
            {
                // Check if the cluster is up
                Pair<Master, Machine> master = broker.getMasterReally();
                if ( master != null && master.first() != null )
                { // Join the existing cluster
                    try
                    {
                        copyStoreFromMaster( master );
                        msgLog.logMessage( "copied store from master" );
                        exception = null;
                        break;
                    }
                    catch ( Exception e )
                    {
                        exception = e;
                        master = broker.getMasterReally();
                        msgLog.logMessage( "Problems copying store from master", e );
                    }
                }
                else if ( allowInit )
                { // Try to initialize the cluster and become master
                    exception = null;
                    StoreId myStoreId = new StoreId();
                    storeId = broker.createCluster( myStoreId );
                    if ( storeId.equals( myStoreId ) )
                    { // I am master
                        break;
                    }
                }
                // I am not master, and could not connect to the master:
                // wait for other machine(s) to join.
                sleepWithoutInterruption( 300, "Startup interrupted" );
            }

            if ( exception != null )
            {
                throw new RuntimeException( "Tried to join the cluster, but was unable to", exception );
            }
        }
        newMaster( null, storeId, new Exception() );
        localGraph();
    }

    private void sleepWithoutInterruption( long time, String errorMessage )
    {
        try
        {
            Thread.sleep( time );
        }
        catch ( InterruptedException e )
        {
            throw new RuntimeException( errorMessage, e );
        }
    }

    private void copyStoreFromMaster( Pair<Master, Machine> master ) throws Exception
    {
        msgLog.logMessage( "Copying store from master" );
        Response<Void> response = master.first().copyStore( new SlaveContext( 0, machineId, 0, new Pair[0] ),
                new ToFileStoreWriter( storeDir ) );
        long highestLogVersion = highestLogVersion();
        if ( highestLogVersion > -1 ) NeoStore.setVersion( storeDir, highestLogVersion + 1 );
        EmbeddedGraphDatabase copiedDb = new EmbeddedGraphDatabase( storeDir, stringMap( KEEP_LOGICAL_LOGS, "true" ) );
        try
        {
            MasterUtil.applyReceivedTransactions( response, copiedDb, MasterUtil.txHandlerForFullCopy() );
        }
        finally
        {
            copiedDb.shutdown();
        }
        msgLog.logMessage( "Done copying store from master" );
    }

    private long highestLogVersion()
    {
        Pattern logFilePattern = getHistoryFileNamePattern( LOGICAL_LOG_DEFAULT_NAME );
        long highest = -1;
        for ( File file : new File( storeDir ).listFiles() )
        {
            if ( logFilePattern.matcher( file.getName() ).matches() )
            {
                highest = max( highest, getHistoryLogVersion( file ) );
            }
        }
        return highest;
    }

    private EmbeddedGraphDbImpl localGraph()
    {
        if ( localGraph == null )
        {
            if ( causeOfShutdown != null )
            {
                throw new RuntimeException( "Graph database not started", causeOfShutdown );
            }
            else
            {
                throw new RuntimeException( "Graph database not assigned and no cause of shutdown, " +
                		"maybe not started yet or in the middle of master/slave swap?" );
            }
        }
        return localGraph;
    }

    private BrokerFactory defaultBrokerFactory( final GraphDatabaseService graphDb,
            final Map<String, String> config )
    {
        return new BrokerFactory()
        {
            @Override
            public Broker create( GraphDatabaseService graphDb, Map<String, String> config )
            {
                return new ZooKeeperBroker( graphDb, machineId, config, HighlyAvailableGraphDatabase.this );
            }
        };
    }

    private BranchedDataPolicy getBranchedDataPolicyFromConfig( Map<String, String> config )
    {
        return config.containsKey( HaConfig.CONFIG_KEY_BRANCHED_DATA_POLICY ) ?
                BranchedDataPolicy.valueOf( config.get( HaConfig.CONFIG_KEY_BRANCHED_DATA_POLICY ) ) :
                BranchedDataPolicy.keep_all;
    }

    private static boolean getAllowInitFromConfig( Map<?, ?> config )
    {
        String allowInit = (String) config.get( HaConfig.CONFIG_KEY_ALLOW_INIT_CLUSTER );
        if ( allowInit == null ) return true;
        return Boolean.parseBoolean( allowInit );
    }

    public Broker getBroker()
    {
        return this.broker;
    }

    public void pullUpdates()
    {
        try
        {
            if ( masterServer == null )
            {
                receive( broker.getMaster().first().pullUpdates( getSlaveContext( -1 ) ) );
            }
        }
        catch ( ZooKeeperException e )
        {
            newMaster( null, e );
            throw e;
        }
        catch ( ComException e )
        {
            newMaster( null, e );
            throw e;
        }
    }

    private void updateTime()
    {
        this.updateTime = System.currentTimeMillis();
    }

    long lastUpdateTime()
    {
        return this.updateTime;
    }

    @Override
    public Config getConfig()
    {
        return localGraph().getConfig();
    }

    @Override
    public String getStoreDir()
    {
        return this.storeDir;
    }

    @Override
    public <T> Collection<T> getManagementBeans( Class<T> type )
    {
        return localGraph().getManagementBeans( type );
    }

    protected synchronized void reevaluateMyself( Pair<Master, Machine> master, StoreId storeId )
    {
        if ( master == null )
        {
            master = broker.getMasterReally();
        }
        boolean restarted = false;
        boolean iAmCurrentlyMaster = masterServer != null;
        msgLog.logMessage( "ReevaluateMyself: machineId=" + machineId + " with master[" + master +
                "] (I am master=" + iAmCurrentlyMaster + ", " + localGraph + ")" );
        if ( master.other().getMachineId() == machineId )
        {   // I am the new master
            if ( this.localGraph == null || !iAmCurrentlyMaster )
            {   // I am currently a slave, so restart as master
                internalShutdown( true );
                startAsMaster( storeId );
                restarted = true;
            }
            // fire rebound event
            broker.rebindMaster();
        }
        else
        {   // Someone else is the new master
            broker.notifyMasterChange( master.other() );
            if ( this.localGraph == null || iAmCurrentlyMaster )
            {   // I am currently master, so restart as slave.
                // This will result in clearing of free ids from .id files, see SlaveIdGenerator.
                internalShutdown( true );
                startAsSlave( storeId );
                restarted = true;
            }
            else
            {   // I am already a slave, so just forget the ids I got from the previous master
                ((SlaveIdGeneratorFactory) getConfig().getIdGeneratorFactory()).forgetIdAllocationsFromMaster();
            }

            tryToEnsureIAmNotABrokenMachine( master );
        }
        if ( restarted )
        {
            doAfterLocalGraphStarted();
        }
    }

    private void doAfterLocalGraphStarted()
    {
        broker.setConnectionInformation( this.localGraph.getKernelData() );
        for ( TransactionEventHandler<?> handler : transactionEventHandlers )
        {
            localGraph().registerTransactionEventHandler( handler );
        }
        for ( KernelEventHandler handler : kernelEventHandlers )
        {
            localGraph().registerKernelEventHandler( handler );
        }
    }

    private void startAsSlave( StoreId storeId )
    {
        msgLog.logMessage( "Starting[" + machineId + "] as slave", true );
        this.localGraph = new EmbeddedGraphDbImpl( storeDir, storeId, config, this,
                new SlaveLockManagerFactory( broker, this ),
                new SlaveIdGeneratorFactory( broker, this ),
                new SlaveRelationshipTypeCreator( broker, this ),
                new SlaveTxIdGeneratorFactory( broker, this ),
                new SlaveTxFinishHook( broker, this ),
                slaveUpdateMode.createUpdater( broker ),
                CommonFactories.defaultFileSystemAbstraction() );
        instantiateAutoUpdatePullerIfConfigSaysSo();
        msgLog.logMessage( "Started as slave", true );
    }

    private void startAsMaster( StoreId storeId )
    {
        msgLog.logMessage( "Starting[" + machineId + "] as master", true );
        this.localGraph = new EmbeddedGraphDbImpl( storeDir, storeId, config, this,
                CommonFactories.defaultLockManagerFactory(),
                new MasterIdGeneratorFactory(),
                CommonFactories.defaultRelationshipTypeCreator(),
                new MasterTxIdGeneratorFactory( broker ),
                CommonFactories.defaultTxFinishHook(),
                new ZooKeeperLastCommittedTxIdSetter( broker ),
                CommonFactories.defaultFileSystemAbstraction() );
        this.masterServer = (MasterServer) broker.instantiateMasterServer( this );
        msgLog.logMessage( "Started as master", true );
    }

    private void tryToEnsureIAmNotABrokenMachine( Pair<Master, Machine> master )
    {
        if ( master.other().getMachineId() == machineId )
        {
            return;
        }
        else if ( master.first() == null )
        {
            // Temporarily disconnected from ZK
            RuntimeException cause = new RuntimeException( "Unable to get master from ZK" );
            shutdown( cause, false );
            throw cause;
        }

        XaDataSource nioneoDataSource = getConfig().getTxModule()
                .getXaDataSourceManager().getXaDataSource( Config.DEFAULT_DATA_SOURCE_NAME );
        long myLastCommittedTx = nioneoDataSource.getLastCommittedTxId();
        int masterForMyHighestCommonTxId = -1;
        try
        {
            masterForMyHighestCommonTxId = nioneoDataSource.getMasterForCommittedTx( myLastCommittedTx );
        }
        catch ( IOException e )
        {
            // This is quite dangerous to just catch here... but the special case is
            // where this db was just now copied from the master where there's data,
            // but no logical logs were transfered and hence no masterId info is here
            msgLog.logMessage( "Couldn't get master ID for txId " + myLastCommittedTx +
                    ". It may be that a log file is missing due to the db being copied from master?", e );
//            throw new RuntimeException( e );
            return;
        }

        int masterForMastersHighestCommonTxId = master.first().getMasterIdForCommittedTx( myLastCommittedTx ).response();

        // Compare those two, if equal -> good
        if ( masterForMyHighestCommonTxId == XaLogicalLog.MASTER_ID_REPRESENTING_NO_MASTER // means that tx has been was recovered
                || masterForMyHighestCommonTxId == masterForMastersHighestCommonTxId )
        {
            msgLog.logMessage( "Master id for last committed tx ok with highestCommonTxId=" +
                    myLastCommittedTx + " with masterId=" + masterForMyHighestCommonTxId, true );
            return;
        }
        else
        {
            String msg = "Branched data, I (machineId:" + machineId + ") think machineId for txId (" +
                    myLastCommittedTx + ") is " + masterForMyHighestCommonTxId + ", but master (machineId:" +
                    master.other().getMachineId() + ") says that it's " + masterForMastersHighestCommonTxId;
            msgLog.logMessage( msg, true );
            RuntimeException exception = new BranchedDataException( msg );
            shutdown( exception, false );
            throw exception;
        }
    }

    private void instantiateAutoUpdatePullerIfConfigSaysSo()
    {
        String pullInterval = this.config.get( HaConfig.CONFIG_KEY_PULL_INTERVAL );
        if ( pullInterval != null )
        {
            long timeMillis = TimeUtil.parseTimeMillis( pullInterval );
            updatePuller = new ScheduledThreadPoolExecutor( 1 );
            updatePuller.scheduleWithFixedDelay( new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        pullUpdates();
                    }
                    catch ( Exception e )
                    {
                        msgLog.logMessage( "Pull updates failed", e  );
                    }
                }
            }, timeMillis, timeMillis, TimeUnit.MILLISECONDS );
        }
    }

    @Override
    public Transaction beginTx()
    {
        return localGraph().beginTx();
    }

    @Override
    public Node createNode()
    {
        return localGraph().createNode();
    }

    @Override
    public Iterable<Node> getAllNodes()
    {
        return localGraph().getAllNodes();
    }

    @Override
    public Node getNodeById( long id )
    {
        return localGraph().getNodeById( id );
    }

    @Override
    public Node getReferenceNode()
    {
        return localGraph().getReferenceNode();
    }

    @Override
    public Relationship getRelationshipById( long id )
    {
        return localGraph().getRelationshipById( id );
    }

    @Override
    public Iterable<RelationshipType> getRelationshipTypes()
    {
        return localGraph().getRelationshipTypes();
    }

    @Override
    public KernelEventHandler registerKernelEventHandler( KernelEventHandler handler )
    {
        this.kernelEventHandlers.add( handler );
        return localGraph().registerKernelEventHandler( handler );
    }

    @Override
    public <T> TransactionEventHandler<T> registerTransactionEventHandler(
            TransactionEventHandler<T> handler )
    {
        this.transactionEventHandlers.add( handler );
        return localGraph().registerTransactionEventHandler( handler );
    }

    public synchronized void internalShutdown( boolean rotateLogs )
    {
        msgLog.logMessage( "Internal shutdown of HA db[" + machineId + "] reference=" + this + ", masterServer=" + masterServer, new Exception( "Internal shutdown" ), true );
        if ( this.updatePuller != null )
        {
            msgLog.logMessage( "Internal shutdown updatePuller", true );
            this.updatePuller.shutdown();
            msgLog.logMessage( "Internal shutdown updatePuller DONE", true );
            this.updatePuller = null;
        }
        if ( this.masterServer != null )
        {
            msgLog.logMessage( "Internal shutdown masterServer", true );
            this.masterServer.shutdown();
            msgLog.logMessage( "Internal shutdown masterServer DONE", true );
            this.masterServer = null;
        }
        if ( this.localGraph != null )
        {
            if ( rotateLogs )
            {
                for ( XaDataSource dataSource : getConfig().getTxModule().getXaDataSourceManager().getAllRegisteredDataSources() )
                {
                    try
                    {
                        dataSource.rotateLogicalLog();
                    }
                    catch ( IOException e )
                    {
                        msgLog.logMessage( "Couldn't rotate logical log for " + dataSource.getName(), e );
                    }
                }
            }
            msgLog.logMessage( "Internal shutdown localGraph", true );
            this.localGraph.shutdown();
            msgLog.logMessage( "Internal shutdown localGraph DONE", true );
            this.localGraph = null;
        }
        msgLog.flush();
        StringLogger.close( storeDir );
    }

    private synchronized void shutdown( Throwable cause, boolean shutdownBroker )
    {
        causeOfShutdown = cause;
        msgLog.logMessage( "Shutdown[" + machineId + "], " + this, true );
        if ( shutdownBroker && this.broker != null )
        {
            this.broker.shutdown();
        }
        internalShutdown( false );
    }

    @Override
    public synchronized void shutdown()
    {
        shutdown( new IllegalStateException( "shutdown called" ), true );
    }

    @Override
    public KernelEventHandler unregisterKernelEventHandler( KernelEventHandler handler )
    {
        return localGraph().unregisterKernelEventHandler( handler );
    }

    @Override
    public <T> TransactionEventHandler<T> unregisterTransactionEventHandler(
            TransactionEventHandler<T> handler )
    {
        return localGraph().unregisterTransactionEventHandler( handler );
    }

    @Override
    public SlaveContext getSlaveContext( int eventIdentifier )
    {
        XaDataSourceManager localDataSourceManager =
            getConfig().getTxModule().getXaDataSourceManager();
        Collection<XaDataSource> dataSources = localDataSourceManager.getAllRegisteredDataSources();
        @SuppressWarnings("unchecked")
        Pair<String, Long>[] txs = new Pair[dataSources.size()];
        int i = 0;
        for ( XaDataSource dataSource : dataSources )
        {
            txs[i++] = Pair.of( dataSource.getName(), dataSource.getLastCommittedTxId() );
        }
        return new SlaveContext( startupTime, machineId, eventIdentifier, txs );
    }

    @Override
    public <T> T receive( Response<T> response )
    {
        try
        {
            MasterUtil.applyReceivedTransactions( response, this, MasterUtil.NO_ACTION );
            updateTime();
            return response.response();
        }
        catch ( IOException e )
        {
            newMaster( null, e );
            throw new RuntimeException( e );
        }
    }

    @Override
    public void newMaster( Pair<Master, Machine> master, Exception e )
    {
        newMaster( master, null, e );
    }

    private synchronized void newMaster( Pair<Master, Machine> master, StoreId storeId, Exception e )
    {
        try
        {
            doNewMaster( master, storeId, e );
        }
        catch ( BranchedDataException bde )
        {
            msgLog.logMessage( "Branched data occured, retrying" );
            getFreshDatabaseFromMaster( master );
            doNewMaster( master, storeId, bde );
        }
    }

    private void doNewMaster( Pair<Master, Machine> master, StoreId storeId, Exception e )
    {
        try
        {
            msgLog.logMessage( "newMaster(" + master + ") called", true );
            reevaluateMyself( master, storeId );
        }
        catch ( ZooKeeperException ee )
        {
            broker.getMasterReally();
            msgLog.logMessage( "ZooKeeper exception in newMaster", ee );
        }
        catch ( ComException ee )
        {
            broker.getMasterReally();
            msgLog.logMessage( "Communication exception in newMaster", ee );
        }
        catch ( BranchedDataException ee )
        {
            throw ee;
        }
        // BranchedDataException will escape from this method since the catch clause below
        // sees to that.
        catch ( Throwable t )
        {
            broker.getMasterReally();
            msgLog.logMessage( "Reevaluation ended in unknown exception " + t
                    + " so shutting down", t, true );
            shutdown( t, false );
            throw Exceptions.launderedException( t );
        }
    }

    public MasterServer getMasterServerIfMaster()
    {
        return masterServer;
    }

    int getMachineId()
    {
        return machineId;
    }

    public boolean isMaster()
    {
        return broker.iAmMaster();
    }

    @Override
    public boolean isReadOnly()
    {
        return false;
    }

    @Override
    public IndexManager index()
    {
        return localGraph().index();
    }

    // Only for testing purposes, simulates a network outage almost
    public void shutdownBroker()
    {
        this.broker.shutdown();
    }

    enum BranchedDataPolicy
    {
        keep_all
        {
            @Override
            void handle( HighlyAvailableGraphDatabase db )
            {
                moveAwayDb( db, branchedDataDir( db ) );
            }
        },
        keep_last
        {
            @Override
            void handle( HighlyAvailableGraphDatabase db )
            {
                File branchedDataDir = branchedDataDir( db );
                moveAwayDb( db, branchedDataDir );
                for ( File file : new File( db.storeDir ).listFiles() )
                {
                    if ( isBranchedDataDirectory( file ) && !file.equals( branchedDataDir ) )
                    {
                        try
                        {
                            FileUtils.deleteRecursively( file );
                        }
                        catch ( IOException e )
                        {
                            db.msgLog.logMessage( "Couldn't delete old branched data directory " + file, e );
                        }
                    }
                }
            }
        },
        keep_none
        {
            @Override
            void handle( HighlyAvailableGraphDatabase db )
            {
                for ( File file : relevantDbFiles( db ) )
                {
                    try
                    {
                        FileUtils.deleteRecursively( file );
                    }
                    catch ( IOException e )
                    {
                        db.msgLog.logMessage( "Couldn't delete file " + file, e );
                    }
                }
            }
        },
        shutdown
        {
            @Override
            void handle( HighlyAvailableGraphDatabase db )
            {
                db.shutdown();
            }
        };
        
        static String BRANCH_PREFIX = "branched-";

        abstract void handle( HighlyAvailableGraphDatabase db );
        
        protected void moveAwayDb( HighlyAvailableGraphDatabase db, File branchedDataDir )
        {
            for ( File file : relevantDbFiles( db ) )
            {
                File dest = new File( branchedDataDir, file.getName() );
                if ( !file.renameTo( dest ) ) db.msgLog.logMessage( "Couldn't move " + file.getPath() );
            }
        }

        File branchedDataDir( HighlyAvailableGraphDatabase db )
        {
            File result = new File( db.storeDir, BRANCH_PREFIX + System.currentTimeMillis() );
            result.mkdirs();
            return result;
        }
        
        File[] relevantDbFiles( HighlyAvailableGraphDatabase db )
        {
            return new File( db.storeDir ).listFiles( new FileFilter()
            {
                @Override
                public boolean accept( File file )
                {
                    return !file.equals( StringLogger.DEFAULT_NAME ) && !isBranchedDataDirectory( file );
                }
            } );
        }

        boolean isBranchedDataDirectory( File file )
        {
            return file.isDirectory() && file.getName().startsWith( BRANCH_PREFIX );
        }
    }

}
