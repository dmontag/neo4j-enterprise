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
package org.neo4j.server.enterprise;

import org.neo4j.kernel.ha.HaConfig;
import org.neo4j.server.configuration.Configurator;
import org.neo4j.server.startup.healthcheck.Neo4jPropertiesMustExistRule;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Neo4jHAPropertiesMustExistRule extends Neo4jPropertiesMustExistRule
{
    @Override
    protected boolean validateProperties( Properties configProperties )
    {
        String dbMode = configProperties.getProperty( Configurator.DB_MODE_KEY,
                EnterpriseNeoServerBootstrapper.DatabaseMode.SINGLE.name() );
        dbMode = dbMode.toUpperCase();
        if ( dbMode.equals( EnterpriseNeoServerBootstrapper.DatabaseMode.SINGLE.name() ) ) return true;
        if ( !dbMode.equals( EnterpriseNeoServerBootstrapper.DatabaseMode.HA.name() ) )
        {
            failureMessage = String.format( "Illegal value for %s \"%s\" in %s", Configurator.DB_MODE_KEY, dbMode,
                    Configurator.NEO_SERVER_CONFIG_FILE_KEY );
            return false;
        }

        String dbTuningFilename = configProperties.getProperty( Configurator.DB_TUNING_PROPERTY_FILE_KEY );
        if ( dbTuningFilename == null )
        {
            failureMessage = String.format( "High-Availability mode requires %s to be set in %s",
                    Configurator.DB_TUNING_PROPERTY_FILE_KEY, Configurator.NEO_SERVER_CONFIG_FILE_KEY );
            return false;
        }
        else
        {
            File dbTuningFile = new File( dbTuningFilename );
            if ( !dbTuningFile.exists() )
            {
                failureMessage = String.format( "No database tuning file at [%s]", dbTuningFile.getAbsoluteFile() );
                return false;
            }
            else
            {
                Properties dbTuning = new Properties();
                try
                {
                    InputStream tuningStream = new FileInputStream( dbTuningFile );
                    try
                    {
                        dbTuning.load( tuningStream );
                    }
                    finally
                    {
                        tuningStream.close();
                    }
                }
                catch ( IOException e )
                {
                    // Shouldn't happen, we already covered those cases
                    failureMessage = e.getMessage();
                    return false;
                }
                String machineId = dbTuning.getProperty( HaConfig.CONFIG_KEY_SERVER_ID,
                        dbTuning.getProperty( HaConfig.CONFIG_KEY_OLD_SERVER_ID, "<not set>" ) );
                try
                {
                    if ( Integer.parseInt( machineId ) < 0 ) throw new NumberFormatException();
                }
                catch ( NumberFormatException e )
                {
                    failureMessage = String.format( "%s in %s needs to be a non-negative integer, not %s",
                            HaConfig.CONFIG_KEY_SERVER_ID, dbTuningFilename, machineId );
                    return false;
                }
                String[] zkServers = dbTuning.getProperty(
                        HaConfig.CONFIG_KEY_COORDINATORS,
                        dbTuning.getProperty( HaConfig.CONFIG_KEY_OLD_COORDINATORS, "" ) ).split( "," );
                if ( zkServers.length <= 0 )
                {
                    failureMessage = String.format( "%s in %s needs to specify at least one server",
                            HaConfig.CONFIG_KEY_SERVER_ID, dbTuningFilename );
                    return false;
                }
                for ( String zk : zkServers )
                {
                    if ( !zk.contains( ":" ) )
                    {
                        failureMessage = String.format( "Invalid server config \"%s\" for %s in %s", zk,
                                HaConfig.CONFIG_KEY_SERVER_ID, dbTuningFilename );
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
