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
package just.testing;

import static java.lang.Thread.sleep;
import static just.testing.WriteSomeStuff.BASE_URI;
import static just.testing.WriteSomeStuff.CLIENT;
import static just.testing.WriteSomeStuff.RANDOM;

import javax.ws.rs.core.MediaType;

import org.neo4j.server.rest.domain.JsonHelper;
import org.neo4j.server.rest.web.PropertyValueException;

import com.sun.jersey.api.client.ClientResponse;

public class ReadSomeStuff
{
    public static void main( String[] args ) throws Exception
    {
        while ( true )
        {
            doShortestPath( findRandomNode(), findRandomNode() );
            sleep( 500 );
        }
    }
    
    private static void doShortestPath( String from, String to ) throws PropertyValueException
    {
        String json = "{\"to\":\"" + to + "\", \"max depth\":100, \"algorithm\":\"shortestPath\"}";
        ClientResponse response = CLIENT.resource( from + "/paths" ).accept( MediaType.APPLICATION_JSON_TYPE )
                .entity( json, MediaType.APPLICATION_JSON_TYPE ).post( ClientResponse.class );
        String entity = response.getEntity( String.class );
        int length = 0;
        if ( entity.startsWith( "[" ) )
        {
            length = JsonHelper.jsonToList( json ).size();
        }
        else
        {
            length = 1;
        }
        System.out.println( "Found " + length + " paths between " + from + " and " + to );
        response.close();
    }
    
    static String findRandomNode()
    {
        while ( true )
        {
            String randomNodeUri = BASE_URI + "node/" + RANDOM.nextInt( 10000 );
            ClientResponse response = CLIENT.resource( randomNodeUri )
                    .accept( MediaType.APPLICATION_JSON_TYPE ).get( ClientResponse.class );
            try
            {
                if ( response.getStatus() == 200 )
                {
                    return randomNodeUri;
                }
            }
            finally
            {
                response.close();
            }
        }
    }
}
