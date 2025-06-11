/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.rest.data

import com.normation.errors.*
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAccountId
import com.normation.rudder.api.ApiAccountKind
import com.normation.rudder.api.ApiAccountKind.PublicApi
import com.normation.rudder.api.ApiAccountName
import com.normation.rudder.api.ApiAccountType
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.api.ApiAuthorizationKind
import com.normation.rudder.api.ApiTokenHash
import com.normation.rudder.api.ApiTokenSecret
import com.normation.rudder.api.TokenGenerator
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.repository.ldap.JsonApiAcl
import com.normation.rudder.rest.data.NewRestApiAccount.transformNewRestApiAccount
import com.normation.utils.DateFormaterService.DateTimeCodecs
import com.normation.utils.StringUuidGenerator
import com.softwaremill.quicklens.*
import enumeratum.Enum
import enumeratum.EnumEntry
import io.scalaland.chimney.*
import io.scalaland.chimney.partial.Result
import io.scalaland.chimney.syntax.*
import java.time.ZonedDateTime
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.json.*
import zio.json.internal.Write
import zio.syntax.*

/*
 * Data for API accounts.
 * We are a bit more careful about what is sent because we don't ever want to send
 * back Token by API apart when generated.
 * Same, data creation and update is a bit different for API account.
 */

/**
 * Account status: enabled or not
 */
sealed trait ApiAccountStatus extends EnumEntry with EnumEntry.Lowercase
object ApiAccountStatus       extends Enum[ApiAccountStatus] {
  case object Enabled  extends ApiAccountStatus
  case object Disabled extends ApiAccountStatus

  override def values: IndexedSeq[ApiAccountStatus] = findValues

  implicit val codecApiAccountStatus: JsonCodec[ApiAccountStatus] = new JsonCodec[ApiAccountStatus](
    JsonEncoder.string.contramap(_.entryName),
    JsonDecoder.string.mapOrFail(withNameInsensitiveEither(_).left.map(_.getMessage()))
  )
}

/**
 * Token state: undef, generated
 */
sealed trait ApiTokenState extends EnumEntry with EnumEntry.Lowercase
object ApiTokenState       extends Enum[ApiTokenState] {
  case object Undef       extends ApiTokenState
  case object GeneratedV1 extends ApiTokenState
  case object GeneratedV2 extends ApiTokenState

  override def values: IndexedSeq[ApiTokenState] = findValues

  implicit val codecApiTokenState: JsonCodec[ApiTokenState] = new JsonCodec[ApiTokenState](
    JsonEncoder.string.contramap(_.entryName),
    JsonDecoder.string.mapOrFail(withNameInsensitiveEither(_).left.map(_.getMessage()))
  )
}

sealed trait ApiAccountExpirationPolicy extends EnumEntry with EnumEntry.Lowercase
object ApiAccountExpirationPolicy       extends Enum[ApiAccountExpirationPolicy] {

  case object Never      extends ApiAccountExpirationPolicy
  case object AtDateTime extends ApiAccountExpirationPolicy {
    override def entryName: String = "datetime"
  }

  override def values: IndexedSeq[ApiAccountExpirationPolicy] = findValues

  implicit val codecApiTokenExpirationPolicy: JsonCodec[ApiAccountExpirationPolicy] = new JsonCodec[ApiAccountExpirationPolicy](
    JsonEncoder.string.contramap(_.entryName),
    JsonDecoder.string.mapOrFail(withNameInsensitiveEither(_).left.map(_.getMessage()))
  )
}

// encapsulate Option[JsonAcl] into a type so that it's easier to map to business object
object MaybeAcl {
  type tpeFrom = Option[JsonApiAcl]
  type tpeTo   = Option[List[ApiAclElement]]

  implicit val transformtpe: PartialTransformer[tpeFrom, tpeTo] = {
    PartialTransformer.apply[tpeFrom, tpeTo] {
      case None      => Result.Value(None)
      case Some(acl) => acl.transformIntoPartial[List[ApiAclElement]].map(Some.apply)
    }
  }
}

import MaybeAcl.transformtpe

// Output DATA

// default codecs for API accounts
trait ApiAccountCodecs extends DateTimeCodecs {

  implicit val accountIdEncoder:            JsonEncoder[ApiAccountId]         = JsonEncoder.string.contramap(_.value)
  implicit val decoderApiAccountId:         JsonDecoder[ApiAccountId]         =
    JsonDecoder.string.mapOrFail(ApiAccountId.parse(_).left.map(_.fullMsg))
  implicit val accountNameEncoder:          JsonEncoder[ApiAccountName]       = JsonEncoder.string.contramap(_.value)
  implicit val decoderApiAccountName:       JsonDecoder[ApiAccountName]       = JsonDecoder.string.map(ApiAccountName.apply)
  implicit val accountTypeEncoder:          JsonEncoder[ApiAccountType]       = JsonEncoder.string.contramap(_.name)
  implicit val decoderApiAccountType:       JsonDecoder[ApiAccountType]       =
    JsonDecoder.string.mapOrFail(ApiAccountType.parse(_).left.map(_.fullMsg))
  implicit val authorizationTypeEncoder:    JsonEncoder[ApiAuthorizationKind] = JsonEncoder.string.contramap(_.name)
  implicit val decoderApiAuthorizationKind: JsonDecoder[ApiAuthorizationKind] =
    JsonDecoder.string.mapOrFail(ApiAuthorizationKind.parse)
  implicit val encoderNodeSecurityContext:  JsonEncoder[NodeSecurityContext]  = JsonEncoder.string.contramap(_.serialize)
  implicit val decoderNodeSecurityContext:  JsonDecoder[NodeSecurityContext]  =
    JsonDecoder.string.mapOrFail(s => NodeSecurityContext.parse(Some(s)).left.map(_.fullMsg))
}

sealed trait ApiAccountDetails {
  def id:                  ApiAccountId
  def name:                ApiAccountName               // used in event log to know who did actions.
  def description:         String
  def status:              ApiAccountStatus
  def creationDate:        ZonedDateTime                // this is the account creation date, mapped to creationTimestamp
  def expirationPolicy:    ApiAccountExpirationPolicy   // this is expiration for the whole account
  def expirationDate:      Option[ZonedDateTime]        // this is expiration for the whole account
  def tokenState:          ApiTokenState
  def tokenGenerationDate: Option[ZonedDateTime]        // this is the token generation date, mapped to apiTokenCreationTimestamp
  def tenants:             NodeSecurityContext
  def authorizationType:   Option[ApiAuthorizationKind] // ApiAuthorization.kind
  def acl:                 Option[JsonApiAcl]
}

final case class ClearTextSecret(value: String)
object ClearTextSecret {
  implicit val encoderClearTextSecret:        JsonEncoder[ClearTextSecret]                 = JsonEncoder.string.contramap(_.value)
  implicit val transformerFromApiTokenSecret: Transformer[ApiTokenSecret, ClearTextSecret] = apiTokenSecret =>
    ClearTextSecret(apiTokenSecret.exposeSecret())
  implicit val transformer:                   Transformer[ClearTextSecret, ApiTokenSecret] = clearText => ApiTokenSecret(clearText.value)
}

object ApiAccountDetails extends ApiAccountCodecs {

  /**
   * General data structure about a public API account details. Some notes:
   * - kind is not returned (only public accounts are returned here)
   * - token value is never returned here, in place token info is returned:
   *   - a token can not be generated for the account
   *   - if it is, then we also return the generation date,
   *   - a generated token has an expiration policy,
   *   - if it is generated and it expires, we return the expiration date
   */
  final case class Public(
      id:                  ApiAccountId,
      name:                ApiAccountName,
      description:         String,
      status:              ApiAccountStatus,
      creationDate:        ZonedDateTime,
      expirationPolicy:    ApiAccountExpirationPolicy,
      expirationDate:      Option[ZonedDateTime],
      tokenState:          ApiTokenState,
      tokenGenerationDate: Option[ZonedDateTime],
      tenants:             NodeSecurityContext,
      authorizationType:   Option[ApiAuthorizationKind],
      acl:                 Option[JsonApiAcl]
  ) extends ApiAccountDetails

  final case class WithToken(
      id:                  ApiAccountId,
      name:                ApiAccountName,
      description:         String,
      status:              ApiAccountStatus,
      creationDate:        ZonedDateTime,
      expirationPolicy:    ApiAccountExpirationPolicy,
      expirationDate:      Option[ZonedDateTime],
      tokenState:          ApiTokenState,
      tokenGenerationDate: Option[ZonedDateTime],
      token:               ClearTextSecret,
      tenants:             NodeSecurityContext,
      authorizationType:   Option[ApiAuthorizationKind],
      acl:                 Option[JsonApiAcl]
  ) extends ApiAccountDetails

  // only encode, no decoder for that
  implicit val encoderApiAccountDetailsPublic:    JsonEncoder[ApiAccountDetails.Public]              = DeriveJsonEncoder.gen
  implicit val encoderApiAccountDetailsWithToken: JsonEncoder[ApiAccountDetails.WithToken]           = DeriveJsonEncoder.gen
  implicit val encoderApiAccountDetails:          JsonEncoder[ApiAccountDetails]                     = new JsonEncoder[ApiAccountDetails] {
    override def unsafeEncode(a: ApiAccountDetails, indent: Option[Int], out: Write): Unit = {
      a match {
        case x: ApiAccountDetails.Public    => encoderApiAccountDetailsPublic.unsafeEncode(x, indent, out)
        case x: ApiAccountDetails.WithToken => encoderApiAccountDetailsWithToken.unsafeEncode(x, indent, out)
      }
    }
  }
  implicit val transformApiAccountKindExpDT:      Transformer[ApiAccountKind, Option[ZonedDateTime]] = {
    case ApiAccountKind.System | ApiAccountKind.User => None
    case PublicApi(_, expirationDate)                => expirationDate.map(_.transformInto[ZonedDateTime])
  }

  implicit val transformApiAccountKindExpPol: Transformer[ApiAccountKind, ApiAccountExpirationPolicy] = {
    case PublicApi(_, expirationDate) if (expirationDate.isDefined) => ApiAccountExpirationPolicy.AtDateTime
    case _                                                          => ApiAccountExpirationPolicy.Never
  }

  implicit val transformApiAclElement: Transformer[List[ApiAclElement], JsonApiAcl] = JsonApiAcl.from _

  // authorization name is only defined for public API
  implicit val transformApiAccountKindAuthz: Transformer[ApiAccountKind, Option[ApiAuthorizationKind]] = {
    case ApiAccountKind.System | ApiAccountKind.User => None
    case PublicApi(authz, _)                         => Some(authz.kind)
  }

  // ACLs are only defined for authorization kind = ACLs (and we ungroup path on each verb)
  implicit val transformApiAccountKindAcl: Transformer[ApiAccountKind, Option[JsonApiAcl]] = {
    case PublicApi(ApiAuthorization.ACL(list), _) => Some(list.transformInto[JsonApiAcl])
    case _                                        => None
  }

  private def transformApiAccountDetails[A <: ApiAccountDetails] = Transformer
    .define[ApiAccount, A]
    .withFieldComputed(_.status, x => if (x.isEnabled) ApiAccountStatus.Enabled else ApiAccountStatus.Disabled)
    .withFieldComputed(_.expirationPolicy, _.kind.transformInto[ApiAccountExpirationPolicy])
    .withFieldComputed(_.expirationDate, _.kind.transformInto[Option[ZonedDateTime]])
    .withFieldComputed(
      _.tokenState,
      x => {
        x.token match {
          case Some(t) if t.version() < 2  => ApiTokenState.GeneratedV1
          case Some(t) if t.version() == 2 => ApiTokenState.GeneratedV2
          case _                           => ApiTokenState.Undef
        }
      }
    )
    .withFieldComputed(_.authorizationType, _.kind.transformInto[Option[ApiAuthorizationKind]])
    .withFieldComputed(_.acl, _.kind.transformInto[Option[JsonApiAcl]])

  implicit val transformPublicApi: Transformer[ApiAccount, ApiAccountDetails.Public] = {
    transformApiAccountDetails[ApiAccountDetails.Public]
      .withFieldComputed(_.tokenGenerationDate, x => x.token.map(_ => x.tokenGenerationDate.transformInto[ZonedDateTime]))
      .buildTransformer
  }

  def transformWithTokenApi(secret: ClearTextSecret): Transformer[ApiAccount, ApiAccountDetails.WithToken] = {
    transformApiAccountDetails[ApiAccountDetails.WithToken]
      .withFieldConst(_.token, secret)
      .buildTransformer
  }
}

// Input DATA

/**
 * Things that changed since 8.3 :
 * - no more `oldId` supported
 * - boolean "enabled" is replaced with a status "enabled/disabled"
 * - there is an explicit expirationPolicy in place of expirationDateDefined
 * - expirationDate is an option
 */
final case class NewRestApiAccount(
    id:                Option[ApiAccountId],
    name:              ApiAccountName, // used in event log to know who did actions.
    description:       Option[String],
    status:            ApiAccountStatus,
    tenants:           NodeSecurityContext,
    generateToken:     Option[Boolean],
    expirationPolicy:  Option[ApiAccountExpirationPolicy],
    expirationDate:    Option[ZonedDateTime],
    authorizationType: Option[ApiAuthorizationKind],
    acl:               Option[JsonApiAcl]
)

/**
 * This is the object where lies most of the logic that interprets an API account input
 */
object NewRestApiAccount extends ApiAccountCodecs {
  implicit val decoderNewRestApiAccount: JsonDecoder[NewRestApiAccount] = DeriveJsonDecoder.gen

  // build transformer with dependencies
  def transformNewRestApiAccount(
      d:  DateTime,
      id: ApiAccountId,
      t:  Option[ApiTokenHash]
  ): PartialTransformer[NewRestApiAccount, ApiAccount] = {
    PartialTransformer
      .define[NewRestApiAccount, ApiAccount]
      .withFieldConst(_.id, id)
      .withFieldComputedPartial(
        _.kind,
        x => {
          x.acl.transformIntoPartial[MaybeAcl.tpeTo].map { opt =>
            ApiAccountMapping
              .apiKind(x.authorizationType.getOrElse(ApiAuthorizationKind.None), opt, d, x.expirationPolicy, x.expirationDate)
          }
        }
      )
      .withFieldComputed(_.description, _.description.getOrElse(""))
      .withFieldConst(_.token, t)
      .withFieldComputed(_.isEnabled, _.status == ApiAccountStatus.Enabled)
      .withFieldConst(_.creationDate, d)
      .withFieldConst(_.tokenGenerationDate, d) // should be optional no?
      .buildTransformer
  }
}

final case class UpdateApiAccount(
    name:              Option[ApiAccountName],
    description:       Option[String],
    status:            Option[ApiAccountStatus],
    tenants:           Option[NodeSecurityContext],
    expirationPolicy:  Option[ApiAccountExpirationPolicy],
    expirationDate:    Option[ZonedDateTime],
    authorizationType: Option[ApiAuthorizationKind],
    acl:               Option[JsonApiAcl]
)

object UpdateApiAccount extends ApiAccountCodecs {
  implicit val decoderUpdateApiAccount: JsonDecoder[UpdateApiAccount] = DeriveJsonDecoder.gen
}

/**
 * Transformation service for the part with effects / injection
 * of other needed services
 */
class ApiAccountMapping(
    creationDate:   IOResult[DateTime],
    generateId:     IOResult[ApiAccountId],
    generateSecret: IOResult[ClearTextSecret],
    createToken:    ClearTextSecret => IOResult[ApiTokenHash]
) extends DateTimeCodecs {

  /**
   * Create a new ApiAccount and optionally return the secret used for the token
   */
  def fromNewApiAccount(newApiAccount: NewRestApiAccount): IOResult[(ApiAccount, Option[ClearTextSecret])] = {
    for {
      id     <- newApiAccount.id match {
                  case Some(x) => x.succeed
                  case None    => generateId
                }
      secret <- if (newApiAccount.generateToken.getOrElse(true)) generateSecret.map(Some.apply) else None.succeed
      token  <- secret match {
                  case Some(s) => createToken(s).map(Some.apply)
                  case None    => None.succeed
                }
      d      <- creationDate
      r      <- transformNewRestApiAccount(d, id, token).transform(newApiAccount).toIO
    } yield (r, secret)
  }

  /**
   * Update an ApiAccount from Rest data
   */
  def update(account: ApiAccount, up: UpdateApiAccount): PureResult[ApiAccount] = {
    up.acl
      .transformIntoPartial[MaybeAcl.tpeTo]
      .map { acl =>
        account
          .modify(_.name)
          .setToIfDefined(up.name)
          .modify(_.isEnabled)
          .setToIfDefined(up.status.map(_ == ApiAccountStatus.Enabled))
          .modify(_.tenants)
          .setToIfDefined(up.tenants)
          .modify(_.kind)
          .using {
            case ApiAccountKind.PublicApi(a, e) =>
              val authz = up.authorizationType.map(x => ApiAccountMapping.authz(x, acl))
              val exp   = {
                // if we go from "never" to "datetime" without a date, now + 1 month
                (e, up.expirationPolicy, up.expirationDate) match {
                  case (None, None, None)                                        => None
                  case (_, Some(ApiAccountExpirationPolicy.Never), _)            => None
                  case (_, _, Some(d))                                           => Some(d.transformInto[DateTime])
                  case (None, Some(ApiAccountExpirationPolicy.AtDateTime), None) => Some(DateTime.now(DateTimeZone.UTC))
                  case (Some(e), _, None)                                        => Some(e)
                }
              }
              ApiAccountKind.PublicApi(authz.getOrElse(a), exp)
            case x                              => x
          }
      }
      .toPureResult
  }

  def updateToken(account: ApiAccount): IOResult[(ApiAccount, ClearTextSecret)] = {
    for {
      s <- generateSecret
      t <- createToken(s)
      n <- creationDate
    } yield {
      val a = account.modify(_.token).setTo(Some(t)).modify(_.tokenGenerationDate).setTo(n)
      (a, s)
    }
  }

  def toDetailsWithSecret(account: ApiAccount, secret: ClearTextSecret): ApiAccountDetails.WithToken = {
    ApiAccountDetails.transformWithTokenApi(secret).transform(account)
  }

}

object ApiAccountMapping extends DateTimeCodecs {

  // from an API authz kind and acl, build the public API
  def authz(k: ApiAuthorizationKind, acl: Option[List[ApiAclElement]]): ApiAuthorization = {
    k match {
      case ApiAuthorizationKind.None => ApiAuthorization.None
      case ApiAuthorizationKind.RO   => ApiAuthorization.RO
      case ApiAuthorizationKind.RW   => ApiAuthorization.RW
      case ApiAuthorizationKind.ACL  =>
        acl match {
          case None       => ApiAuthorization.ACL(Nil)
          case Some(list) => ApiAuthorization.ACL(list)
        }
    }
  }

  // if expiration policy is missing, it's datetime by default
  // if expiration date is missing, it's one month ahead by default
  def exp(now: DateTime, policy: Option[ApiAccountExpirationPolicy], date: Option[ZonedDateTime]): Option[DateTime] = {
    policy match {
      case Some(ApiAccountExpirationPolicy.Never) => None
      case _                                      =>
        date match {
          case Some(d) => Some(d.transformInto[DateTime])
          case None    => Some(now.plusMonths(1))
        }
    }
  }

  def apiKind(
      a:       ApiAuthorizationKind,
      acl:     Option[List[ApiAclElement]],
      now:     DateTime,
      expPol:  Option[ApiAccountExpirationPolicy],
      expDate: Option[ZonedDateTime]
  ): ApiAccountKind.PublicApi = {
    ApiAccountKind.PublicApi(authz(a, acl), exp(now, expPol, expDate))
  }

  def build(
      uuidGen:        StringUuidGenerator,
      tokenGenerator: TokenGenerator
  ) = {
    val getNow         = DateTime.now(DateTimeZone.UTC).succeed
    val generateId     = ApiAccountId(uuidGen.newUuid).succeed
    val generateSecret = ApiTokenSecret.generate(tokenGenerator).transformInto[ClearTextSecret].succeed
    def generateToken(secret: ClearTextSecret): IOResult[ApiTokenHash] =
      ApiTokenHash.fromSecret(secret.transformInto[ApiTokenSecret]).succeed

    new ApiAccountMapping(getNow, generateId, generateSecret, generateToken)
  }
}
