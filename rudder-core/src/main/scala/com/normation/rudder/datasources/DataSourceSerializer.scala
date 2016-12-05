/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

import net.liftweb.common._
import net.liftweb.util.ControlHelpers.tryo
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import com.normation.rudder.repository.json.JsonExctractorUtils
import scalaz.Monad
import scala.concurrent.duration.Duration
import net.liftweb.json._

object DataSourceJsonSerializer{
  def serialize(source : DataSource) : JValue = {
    import net.liftweb.json.JsonDSL._
    ( ( "name"        -> source.name.value  )
    ~ ( "id"        -> source.id.value  )
    ~ ( "description" -> source.description )
    ~ ( "type" ->
        ( ( "name" -> source.sourceType.name )
        ~ ( "parameters" -> {
            source.sourceType match {
              case HttpDataSourceType(url,headers,method,checkSsl,path,mode,timeOut) =>
                ( ( "url"        -> url     )
                ~ ( "headers"    -> headers )
                ~ ( "path"       -> path    )
                ~ ( "checkSsl"   -> checkSsl )
                ~ ( "requestTimeout"  -> timeOut.toMinutes )
                ~ ( "requestMethod"  -> method )
                ~ ( "requestMode"  ->
                  ( ( "name" -> mode.name )
                  ~ { mode match {
                      case OneRequestByNode =>
                        JObject(Nil)
                      case OneRequestAllNodes(subPath,nodeAttribute) =>
                        ( ( "path" -> subPath)
                        ~ ( "attribute" -> nodeAttribute)
                        )
                  } } )
                ) )
            }
        } ) )
      )
    ~ ( "runParameters" -> (
        ( "onGeneration" -> source.runParam.onGeneration )
      ~ ( "onNewNode"    -> source.runParam.onNewNode )
      ~ ( "schedule"     -> (
          ( "type" -> (source.runParam.schedule match {
                          case _:Scheduled => "scheduled"
                          case _:NoSchedule => "notscheduled"
                        } ) )
        ~ ( "duration" -> source.runParam.schedule.duration.toMinutes)
        ) )
      ) )
    ~ ( "updateTimeout" -> source.updateTimeOut.toMinutes )
    ~ ( "enabled"    -> source.enabled )
    )
  }
}

trait DataSourceExtractor[M[+_]] extends JsonExctractorUtils[M] {
  import com.normation.utils.Control.sequence

  case class DataSourceRunParamWrapper(
      schedule     : M[DataSourceSchedule]
    , onGeneration : M[Boolean]
    , onNewNode    : M[Boolean]
  ) {
    def to = {
      monad.apply3(schedule, onGeneration, onNewNode){
        case (a,b,c) => DataSourceRunParameters(a,b,c)
      }
    }

    def withBase (base : DataSourceRunParameters) = {
      base.copy(
          getOrElse(schedule, base.schedule)
        , getOrElse(onGeneration,base.onGeneration)
        , getOrElse(onNewNode,base.onNewNode)
      )
    }
  }

  case class DataSourceTypeWrapper(
    url            : M[String]
  , headers        : M[Map[String,String]]
  , httpMethod     : M[String]
  , sslCheck       : M[Boolean]
  , path           : M[String]
  , requestMode    : M[HttpRequestMode]
  , requestTimeout : M[FiniteDuration]
  ) {
    def to = {
      monad.apply7(url,headers,httpMethod,sslCheck,path,requestMode,requestTimeout){
        case (a,b,c,d,e,f,g) => HttpDataSourceType(a,b,c,d,e,f,g)
      }
    }

    def withBase (base : DataSourceType) : DataSourceType = {
      base match {
        case httpBase : HttpDataSourceType =>
          httpBase.copy(
              getOrElse(url           , httpBase.url)
            , getOrElse(headers       , httpBase.headers)
            , getOrElse(httpMethod    , httpBase.httpMethod)
            , getOrElse(sslCheck      , httpBase.sslCheck)
            , getOrElse(path          , httpBase.path)
            , getOrElse(requestMode   , httpBase.requestMode)
            , getOrElse(requestTimeout, httpBase.requestTimeOut)
          )
      }
    }
  }

  case class DataSourceWrapper(
    id            : DataSourceId
  , name          : M[DataSourceName]
  , sourceType    : M[DataSourceTypeWrapper]
  , runParam      : M[DataSourceRunParamWrapper]
  , description   : M[String]
  , enabled       : M[Boolean]
  , updateTimeout : M[FiniteDuration]
  ) {
    def to = {
    val unwrapRunParam = monad.bind(runParam)(_.to)
    val unwrapType = monad.bind(sourceType)(_.to)
      monad.apply6(name, unwrapType, unwrapRunParam,description, enabled, updateTimeout){
        case (a,b,c,d,e,f) => DataSource(id,a,b,c,d,e,f)
      }
    }
    def withBase (base : DataSource) = {
      val unwrapRunParam = monad.map(runParam)(_.withBase(base.runParam))
      val unwrapType = monad.map(sourceType)(_.withBase(base.sourceType))
      base.copy(
          base.id
        , getOrElse(name          , base.name)
        , getOrElse(unwrapType    , base.sourceType)
        , getOrElse(unwrapRunParam, base.runParam)
        , getOrElse(description   , base.description)
        , getOrElse(enabled       , base.enabled)
        , getOrElse(updateTimeout , base.updateTimeOut)
      )
    }
  }

  def extractDuration (value : BigInt) = tryo { FiniteDuration(value.toLong, TimeUnit.MINUTES) }

  def extractDataSource(id : DataSourceId, json : JValue) = {
    for {
      params <- extractDataSourceWrapper(id,json)
    } yield {
      params.to
    }
  }

  def extractDataSourceType(json : JObject) = {
    for {
      params <- extractDataSourceTypeWrapper(json)
    } yield {
      params.to
    }
  }

  def extractDataSourceRunParam(json : JObject) = {
    for {
      params <- extractDataSourceRunParameterWrapper(json)
    } yield {
      params.to
    }
  }

  def extractDataSourceWrapper(id : DataSourceId, json : JValue) : Box[DataSourceWrapper] = {
    for {
      name         <- extractJsonString(json, "name",  x => Full(DataSourceName(x)))
      description  <- extractJsonString(json, "description")
      sourceType   <- extractJsonObj(json, "type", extractDataSourceTypeWrapper(_))
      runParam     <- extractJsonObj(json, "runParameters", extractDataSourceRunParameterWrapper(_))
      timeOut      <- extractJsonBigInt(json, "updateTimeout", extractDuration)
      enabled      <- extractJsonBoolean(json, "enabled")
    } yield {
      DataSourceWrapper(
        id
      , name
      , sourceType
      , runParam
      , description
      , enabled
      , timeOut
      )
    }
  }

  def extractDataSourceRunParameterWrapper(obj : JObject) = {

    def extractSchedule(obj : JObject) = {
        for {
            duration <- extractJsonBigInt(obj, "duration", extractDuration)
            scheduleBase  <- {

              val t = extractJsonString(obj, "type",  _ match {
              case "scheduled" => Full(monad.map(duration)(d => Scheduled(d)))
              case "notscheduled" => Full(monad.map(duration)(d => NoSchedule(d)))
              case _ => Failure("not a valid value for datasource schedule")
              })
              t.map( monad.join(_))
            }
        } yield {
          scheduleBase
      }
    }

    for {
      onGeneration <- extractJsonBoolean(obj, "onGeneration")
      onNewNode    <- extractJsonBoolean(obj, "onNewNode")
      schedule     <- extractJsonObj(obj, "schedule", extractSchedule(_))
    } yield {
      DataSourceRunParamWrapper(
          monad.join(schedule)
        , onGeneration
        , onNewNode
      )
    }
  }

  // A source type is composed of two fields : "name" and "parameters"
  // name allow to determine which kind of datasource we are managing and how to extract paramters
  def extractDataSourceTypeWrapper(obj : JObject) : Box[DataSourceTypeWrapper] = {

    obj \ "name" match {
      case JString(HttpDataSourceType.name) =>

        def extractHttpRequestMode(obj : JObject) : Box[M[HttpRequestMode]] = {
          obj \ "name" match {
            case JString(OneRequestByNode.name) =>
              Full(monad.point(OneRequestByNode))
            case JString(OneRequestAllNodes.name) =>
              for {
                attribute <- extractJsonString(obj, "attribute")
                path <- extractJsonString(obj, "path")
              } yield {
                monad.apply2(path, attribute){case (a,b) => OneRequestAllNodes(a,b)}
              }
            case x => Failure(s"Cannot extract request type from: ${x}")
          }
        }

        def HttpDataSourceParameters (obj : JObject)  = {
          for {
            url      <- extractJsonString(obj, "url")
            path     <- extractJsonString(obj, "path")
            method   <- extractJsonString(obj, "requestMethod")
            checkSsl <- extractJsonBoolean(obj, "checkSsl")
            timeout  <- extractJsonBigInt(obj, "requestTimeout", extractDuration)
            headers  <- extractJsonObj(obj, "headers", {
                                          case container@JObject(fields) =>
                                            val t = sequence(fields.toSeq) {
                                              field => extractJsonString(container, field.name, value => Full((field.name,value)))
                                            }
                                            t
                                       } )
            requestMode <- extractJsonObj(obj, "requestMode", extractHttpRequestMode(_))

          } yield {

            import scalaz.std.list._
            val t = monad.bind(headers)( s => monad.map(monad.sequence[(String,String),List]( s.toList))(_.toMap))
            val unwrapMode = monad.join(requestMode)
            (
                url
              , t
              , method
              , checkSsl
              , path
              , unwrapMode
              , timeout
            )
          }
        }

        for {
          parameters <- extractJsonObj(obj, "parameters", HttpDataSourceParameters)
          url = monad.bind(parameters)(_._1)
          headers = monad.bind(parameters)(_._2)
          method = monad.bind(parameters)(_._3)
          checkSsl = monad.bind(parameters)(_._4)
          path = monad.bind(parameters)(_._5)
          mode = monad.bind(parameters)(_._6)
          timeout = monad.bind(parameters)(_._7)
        } yield {
          DataSourceTypeWrapper(
              url
            , headers
            , method
            , checkSsl
            , path
            , mode
            , timeout
          )
        }

      case x => Failure(s"Cannot extract a data source type from: ${x}")
    }
  }

}
