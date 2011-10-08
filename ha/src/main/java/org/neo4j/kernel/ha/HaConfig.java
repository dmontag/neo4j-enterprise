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
package org.neo4j.kernel.ha;

import org.neo4j.com.Client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.neo4j.backup.OnlineBackupExtension.parsePort;
import static org.neo4j.kernel.Config.ENABLE_ONLINE_BACKUP;

public abstract class HaConfig
{
    public static final String CONFIG_KEY_OLD_SERVER_ID = "ha.machine_id";
    public static final String CONFIG_KEY_SERVER_ID = "ha.server_id";
    public static final String CONFIG_KEY_OLD_COORDINATORS = "ha.zoo_keeper_servers";
    public static final String CONFIG_KEY_COORDINATORS = "ha.coordinators";
    public static final String CONFIG_KEY_SERVER = "ha.server";
    public static final String CONFIG_KEY_CLUSTER_NAME = "ha.cluster_name";
    public static final String CONFIG_KEY_PULL_INTERVAL = "ha.pull_interval";
    public static final String CONFIG_KEY_ALLOW_INIT_CLUSTER = "ha.allow_init_cluster";
    public static final String CONFIG_KEY_MAX_CONCURRENT_CHANNELS_PER_SLAVE = "ha.max_concurrent_channels_per_slave";
    public static final String CONFIG_KEY_BRANCHED_DATA_POLICY = "ha.branched_data_policy";
    public static final String CONFIG_KEY_READ_TIMEOUT = "ha.read_timeout";
    public static final String CONFIG_KEY_COORDINATOR_TIMEOUT = "ha.coordinator_timeout";
    public static final String CONFIG_KEY_SLAVE_COORDINATOR_UPDATE_MODE = "ha.slave_coordinator_update_mode";


    public static final String DEFAULT_HA_CLUSTER_NAME = "neo4j.ha";
    public static final int DEFAULT_HA_PORT = 6361;
    public static final int DEFAULT_COORDINATOR_TIMEOUT = 5000;

    public static int getMachineIdFromConfig( Map<String, String> config )
    {
        // Fail fast if null
        return Integer.parseInt( getConfigValue( config, CONFIG_KEY_SERVER_ID, CONFIG_KEY_OLD_SERVER_ID ) );
    }

    public static int getClientReadTimeoutFromConfig( Map<String, String> config )
    {
        String value = config.get( CONFIG_KEY_READ_TIMEOUT );
        return value != null ? Integer.parseInt( value ) : Client.DEFAULT_READ_RESPONSE_TIMEOUT_SECONDS;
    }

    public static int getCoordinatorTimeoutFromConfig( Map<String, String> config )
    {
        String value = config.get( CONFIG_KEY_COORDINATOR_TIMEOUT );
        return value != null ? Integer.parseInt( value ) * 1000 : DEFAULT_COORDINATOR_TIMEOUT;
    }

    public static int getMaxConcurrentChannelsPerSlaveFromConfig( Map<String, String> config )
    {
        String value = config.get( CONFIG_KEY_MAX_CONCURRENT_CHANNELS_PER_SLAVE );
        return value != null ? Integer.parseInt( value ) : Client.DEFAULT_MAX_NUMBER_OF_CONCURRENT_CHANNELS_PER_CLIENT;
    }
    /**
     * @return the port for the backup server if that is enabled, or 0 if disabled.
     */
    public static int getBackupPortFromConfig( Map<?, ?> config )
    {
        String backupConfig = (String) config.get( ENABLE_ONLINE_BACKUP );
        Integer port = parsePort( backupConfig );
        return port != null ? port : 0;
    }

    public static String getClusterNameFromConfig( Map<?, ?> config )
    {
        String clusterName = (String) config.get( CONFIG_KEY_CLUSTER_NAME );
        if ( clusterName == null ) clusterName = DEFAULT_HA_CLUSTER_NAME;
        return clusterName;
    }

    public static String getHaServerFromConfig( Map<?, ?> config )
    {
        String haServer = (String) config.get( CONFIG_KEY_SERVER );
        if ( haServer == null )
        {
            InetAddress host = null;
            try
            {
                host = InetAddress.getLocalHost();
            }
            catch ( UnknownHostException hostBecomesNull )
            {
                // handled by null check
            }
            if ( host == null )
            {
                throw new IllegalStateException(
                        "Could not auto configure host name, please supply " + CONFIG_KEY_SERVER );
            }
            haServer = host.getHostAddress() + ":" + DEFAULT_HA_PORT;
        }
        return haServer;
    }

    public static String getCoordinatorsFromConfig( Map<String, String> config )
    {
        return getConfigValue( config, CONFIG_KEY_COORDINATORS, CONFIG_KEY_OLD_COORDINATORS );
    }


    public static String getConfigValue( Map<String, String> config, String... oneKeyOutOf/*prioritized in descending order*/ )
    {
        String firstFound = null;
        int foundIndex = -1;
        for ( int i = 0; i < oneKeyOutOf.length; i++ )
        {
            String toTry = oneKeyOutOf[i];
            String value = config.get( toTry );
            if ( value != null )
            {
                if ( firstFound != null ) throw new RuntimeException( "Multiple configuration values set for the same logical key: " + asList( oneKeyOutOf ) );
                firstFound = value;
                foundIndex = i;
            }
        }
        if ( firstFound == null ) throw new RuntimeException( "No configuration set for any of: " + asList( oneKeyOutOf ) );
        if ( foundIndex > 0 ) System.err.println( "Deprecated configuration key '" + oneKeyOutOf[foundIndex] +
                "' used instead of the preferred '" + oneKeyOutOf[0] + "'" );
        return firstFound;
    }

    public static SlaveUpdateMode getSlaveUpdateModeFromConfig( Map<String, String> config )
    {
        return config.containsKey( CONFIG_KEY_SLAVE_COORDINATOR_UPDATE_MODE ) ?
                SlaveUpdateMode.valueOf( config.get( CONFIG_KEY_SLAVE_COORDINATOR_UPDATE_MODE ) ) :
                SlaveUpdateMode.async;
    }


}
