/*
*************************************************************************************
* Copyright 2016 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.datasources

import scala.collection.immutable.TreeMap
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.parameters.Parameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.services.policies.InterpolatedValueCompiler
import com.normation.rudder.services.policies.InterpolationContext
import com.normation.utils.Control._

import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.util.Helpers.tryo
import net.minidev.json.JSONArray
import net.minidev.json.JSONAware
import net.minidev.json.JSONValue
import scalaj.http.Http
import scalaj.http.HttpOptions
import scalaz._
import scalaz.concurrent.Task

/*
 * This file contain the logic to update dataset from an
 * HTTP Datasource.
 * More specifically, it allows to:
 * - query a node endpoint using node specific properties,
 * - select a sub-json,
 * - save it in the node properties.
 */

/*
 * The whole service:
 * - from a datasource and node context,
 * - get the node datasource URL,
 * - query it,
 * - parse the json result,
 * - return a rudder property with the content.
 */
class GetDataset(valueCompiler: InterpolatedValueCompiler) {

  val compiler = new InterpolateNode(valueCompiler)

  def getNode(datasourceName: DataSourceName, datasource: HttpDataSourceType, node: NodeInfo, policyServer: NodeInfo, parameters: Set[Parameter], connectionTimeout: Duration, readTimeOut: Duration): Box[NodeProperty] = {
    for {
      p          <- sequence(parameters.toSeq)(compiler.compileParameters) ?~! "Error when transforming Rudder Parameter for variable interpolation"
      parameters =  p.toMap
      url        <- compiler.compileInput(datasource.url, node, policyServer, parameters) ?~! s"Error when trying to parse URL ${datasource.url}"
      path       <- compiler.compileInput(datasource.path, node, policyServer, parameters) ?~! s"Error when trying to compile JSON path ${datasource.path}"
      time_0     =  System.currentTimeMillis
      body       <- QueryHttp.GET(url, datasource.headers, datasource.sslCheck, connectionTimeout, readTimeOut) ?~! s"Error when fetching data from ${url}"
      _          =  DataSourceTimingLogger.trace(s"[${System.currentTimeMillis - time_0} ms] node '${node.id.value}': GET ${url} // ${path}")
      json       <- body match {
                      case Some(body) => JsonSelect.fromPath(path, body) ?~! s"Error when extracting sub-json at path ${path} from ${body}"
                      case None => Full(Nil)
                    }
    } yield {
      //we only get the first element from the path.
      //if list is empty, return "" (remove property).
      NodeProperty(datasourceName.value, json.headOption.getOrElse(""))
    }
  }

  /**
   * Get information for many nodes.
   * Policy servers for each node must be in the map.
   */
  def getMany(datasource: DataSource, nodes: Seq[NodeId], policyServers: Map[NodeId, NodeInfo], parameters: Set[Parameter]): Box[Task[Box[NodeProperty]]] = {
    ???
  }

}

/*
 * Timeout are given in Milleseconds
 */
object QueryHttp {

  /*
   * Simple synchronous http get, return the response
   * body as a string.
   */
  def GET(url: String, headers: Map[String, String], checkSsl: Boolean, connectionTimeout: Duration, readTimeOut: Duration): Box[Option[String]] = {
    val options = (
        HttpOptions.connTimeout(connectionTimeout.toMillis.toInt)
     :: HttpOptions.readTimeout(readTimeOut.toMillis.toInt)
     :: (if(checkSsl) {
          Nil
        } else {
          HttpOptions.allowUnsafeSSL :: Nil
        })
    )

    val client = Http(url).headers(headers).options(options)

    for {
      response <- tryo { client.asString }
      result   <- if(response.isSuccess) {
                    Full(Some(response.body))
                  } else {
                    // If we have a 404 response, we need to remove the property from datasource by setting an empty string here
                    if (response.code == 404) {
                      Full(None)
                    } else {
                      Failure(s"Failure updating datasource with URL '${url}': code ${response.code}: ${response.body}")
                    }
                  }
    } yield {
      result
    }
  }
}

/**
 * A little service that allows the interpolation of a
 * string with node properties given all the relevant context:
 * - the node and its policy server infos,
 * - rudder global parameters
 */
class InterpolateNode(compiler: InterpolatedValueCompiler) {

  def compileParameters(parameter: Parameter): Box[(ParameterName, InterpolationContext => Box[String])] = {
    compiler.compile(parameter.value).map(v => (parameter.name, v))
  }

  def compileInput(input: String, node: NodeInfo, policyServer: NodeInfo, parameters: Map[ParameterName, InterpolationContext => Box[String]]): Box[String] = {

    //build interpolation context from node:
    val context = InterpolationContext(node, policyServer, TreeMap[String, Variable](), parameters, 5)

    for {
      compiled <- compiler.compile(input)
      bounded  <- compiled(context)
    } yield {
      bounded
    }
  }
}

/**
 * Service that allows to find sub-part of JSON matching a JSON
 * path as defined in: http://goessner.net/articles/JsonPath/
 */
object JsonSelect {

  /*
   * Configuration for json path:
   * - always return list,
   * - We don't want "SUPPRESS_EXCEPTIONS" because null are returned
   *   in place => better to Box it.
   * - We don't want ALWAYS_RETURN_LIST, because it blindly add an array
   *   around the value, even if the value is already an array.
   */
  val config = Configuration.builder.build()

  /*
   * Return the selection corresponding to path from the string.
   * Fails on bad json or bad path.
   *
   * Always return a list with normalized outputs regarding
   * arrays and string quotation, see JsonPathTest for details.
   *
   * The list may be empty if 0 node matches the results.
   */
  def fromPath(path: String, json: String): Box[List[String]] = {
    for {
      p <- compilePath(path)
      j <- parse(json)
      r <- select(p, j)
    } yield {
      r
    }
  }

  ///                                                       ///
  /// implementation logic - protected visibility for tests ///
  ///                                                       ///

  protected[datasources] def parse(json: String): Box[DocumentContext] = {
    tryo(JsonPath.using(config).parse(json))
  }

  /*
   * Some remarks:
   * - just a name "foo" is interpreted as "$.foo"
   */
  protected[datasources] def compilePath(path: String): Box[JsonPath] = {
    tryo(JsonPath.compile(path))
  }

  /*
   * not exposed to user due to risk to not use the correct config
   */
  protected[datasources] def select(path: JsonPath, json: DocumentContext): Box[List[String]] = {

    // so, this lib seems to be a whole can of unconsistancies on String quoting.
    // we would like to NEVER have quoted string if they are not in a JSON object
    // but to have them quoted in json object.
    def toJsonString(a: Any): String = {
      a match {
        case s: String => s
        case x         => JSONValue.toJSONString(x)
      }
    }

    // I didn't find any other way to do that:
    // - trying to parse as Any, then find back the correct JSON type
    //   lead to a mess of quoted strings
    // - just parsing as JSONAware fails on string, int, etc.

    import scala.collection.JavaConverters.asScalaBufferConverter

    for {
      jsonValue <- try {
                     Full(json.read[JSONAware](path))
                   } catch {
                     case ex: ClassCastException =>
                       try {
                         Full(json.read[Any](path).toString)
                       } catch {
                         case NonFatal(ex2) => Failure(s"Error when trying to get path '${path.getPath}': ${ex.getMessage}", Full(ex), Empty)
                       }
                     case NonFatal(ex) => Failure(s"Error when trying to get path '${path.getPath}': ${ex.getMessage}", Full(ex), Empty)
                   }
    } yield {
      jsonValue match {
        case x:JSONArray  => x.asScala.toList.map(toJsonString)
        case x            => toJsonString(x) :: Nil
      }
    }
  }

}
