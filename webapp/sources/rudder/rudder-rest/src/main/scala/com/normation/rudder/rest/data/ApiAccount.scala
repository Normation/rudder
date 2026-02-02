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
import com.normation.errors.IOResult
import com.normation.rudder.api.AccountToken
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAccountExpirationPolicy
import com.normation.rudder.api.ApiAccountExpirationPolicy.*
import com.normation.rudder.api.ApiAccountExpirationPolicyKind
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
import com.normation.rudder.repository.ldap.JsonApiAcl
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.utils.DateFormaterService.*
import com.normation.utils.StringUuidGenerator
import com.softwaremill.quicklens.*
import enumeratum.Enum
import enumeratum.EnumEntry
import io.scalaland.chimney.*
import io.scalaland.chimney.partial.Result
import io.scalaland.chimney.syntax.*
import java.time.Instant
import zio.json.*
import zio.json.enumeratum.*
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
object ApiAccountStatus       extends Enum[ApiAccountStatus] with EnumCodec[ApiAccountStatus] {
  case object Enabled  extends ApiAccountStatus
  case object Disabled extends ApiAccountStatus

  override def values: IndexedSeq[ApiAccountStatus] = findValues
}

/**
 * Token state: undef, generated
 */
sealed trait ApiTokenState extends EnumEntry with EnumEntry.Lowercase
object ApiTokenState       extends Enum[ApiTokenState] with EnumCodec[ApiTokenState] {
  case object Undef       extends ApiTokenState
  case object GeneratedV1 extends ApiTokenState
  case object GeneratedV2 extends ApiTokenState

  override def values: IndexedSeq[ApiTokenState] = findValues
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

  implicit val accountIdEncoder:              JsonEncoder[ApiAccountId]                 = JsonEncoder.string.contramap(_.value)
  implicit val decoderApiAccountId:           JsonDecoder[ApiAccountId]                 =
    JsonDecoder.string.mapOrFail(ApiAccountId.parse(_).left.map(_.fullMsg))
  implicit val accountNameEncoder:            JsonEncoder[ApiAccountName]               = JsonEncoder.string.contramap(_.value)
  implicit val decoderApiAccountName:         JsonDecoder[ApiAccountName]               = JsonDecoder.string.map(ApiAccountName.apply)
  implicit val accountTypeEncoder:            JsonEncoder[ApiAccountType]               = JsonEncoder.string.contramap(_.name)
  implicit val decoderApiAccountType:         JsonDecoder[ApiAccountType]               =
    JsonDecoder.string.mapOrFail(ApiAccountType.parse(_).left.map(_.fullMsg))
  implicit val authorizationTypeEncoder:      JsonEncoder[ApiAuthorizationKind]         = JsonEncoder.string.contramap(_.name)
  implicit val decoderApiAuthorizationKind:   JsonDecoder[ApiAuthorizationKind]         =
    JsonDecoder.string.mapOrFail(ApiAuthorizationKind.parse)
  implicit val codecApiTokenExpirationPolicy: JsonCodec[ApiAccountExpirationPolicyKind] = {
    new JsonCodec[ApiAccountExpirationPolicyKind](
      JsonEncoder.string.contramap(_.entryName),
      JsonDecoder.string.mapOrFail(ApiAccountExpirationPolicyKind.withNameInsensitiveEither(_).left.map(_.getMessage()))
    )
  }
}

sealed trait ApiAccountDetails {
  def id:                  ApiAccountId
  def name:                ApiAccountName                 // used in event log to know who did actions.
  def description:         String
  def status:              ApiAccountStatus
  def creationDate:        Instant                        // this is the account creation date, mapped to creationTimestamp
  def expirationPolicy:    ApiAccountExpirationPolicyKind // this is expiration for the whole account
  def expirationDate:      Option[Instant]                // this is expiration for the whole account
  def tokenState:          ApiTokenState
  def tokenGenerationDate: Option[Instant]                // this is the token generation date, mapped to apiTokenCreationTimestamp
  def lastAuthenticationDate
      : Option[Instant] // this is the last account authentication date, mapped to lastAuthenticationTimestamp
  def tenants:           TenantAccessGrant
  def authorizationType: Option[ApiAuthorizationKind] // ApiAuthorization.kind
  def acl:               Option[JsonApiAcl]
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
      id:                     ApiAccountId,
      name:                   ApiAccountName,
      description:            String,
      status:                 ApiAccountStatus,
      creationDate:           Instant,
      expirationPolicy:       ApiAccountExpirationPolicyKind,
      expirationDate:         Option[Instant],
      tokenState:             ApiTokenState,
      tokenGenerationDate:    Option[Instant],
      lastAuthenticationDate: Option[Instant],
      tenants:                TenantAccessGrant,
      authorizationType:      Option[ApiAuthorizationKind],
      acl:                    Option[JsonApiAcl]
  ) extends ApiAccountDetails

  final case class WithToken(
      id:                     ApiAccountId,
      name:                   ApiAccountName,
      description:            String,
      status:                 ApiAccountStatus,
      creationDate:           Instant,
      expirationPolicy:       ApiAccountExpirationPolicyKind,
      expirationDate:         Option[Instant],
      tokenState:             ApiTokenState,
      tokenGenerationDate:    Option[Instant],
      lastAuthenticationDate: Option[Instant],
      token:                  ClearTextSecret,
      tenants:                TenantAccessGrant,
      authorizationType:      Option[ApiAuthorizationKind],
      acl:                    Option[JsonApiAcl]
  ) extends ApiAccountDetails

  // only encode, no decoder for that
  implicit val encoderClearTextSecret:            JsonEncoder[ClearTextSecret]             = JsonEncoder.string.contramap(_.value)
  implicit val encoderApiAccountDetailsPublic:    JsonEncoder[ApiAccountDetails.Public]    = DeriveJsonEncoder.gen
  implicit val encoderApiAccountDetailsWithToken: JsonEncoder[ApiAccountDetails.WithToken] = DeriveJsonEncoder.gen
  implicit val encoderApiAccountDetails:          JsonEncoder[ApiAccountDetails]           = new JsonEncoder[ApiAccountDetails] {
    override def unsafeEncode(a: ApiAccountDetails, indent: Option[Int], out: Write): Unit = {
      a match {
        case x: ApiAccountDetails.Public    => encoderApiAccountDetailsPublic.unsafeEncode(x, indent, out)
        case x: ApiAccountDetails.WithToken => encoderApiAccountDetailsWithToken.unsafeEncode(x, indent, out)
      }
    }
  }

  implicit val transformApiAclElement: Transformer[List[ApiAclElement], JsonApiAcl] = JsonApiAcl.from

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
    .withFieldRenamed(_.kind, _.expirationPolicy)
    .withFieldComputed(
      _.expirationDate,
      _.kind match {
        case PublicApi(_, expirationPolicy) => expirationPolicy.expirationDate
        case _                              => None
      }
    )
    .withFieldComputed(
      _.tokenState,
      x => {
        x.token match {
          case AccountToken(Some(t), _) if t.version() < 2  => ApiTokenState.GeneratedV1
          case AccountToken(Some(t), _) if t.version() == 2 => ApiTokenState.GeneratedV2
          case _                                            => ApiTokenState.Undef
        }
      }
    )
    .withFieldComputed(_.authorizationType, _.kind.transformInto[Option[ApiAuthorizationKind]])
    .withFieldComputed(
      _.tokenGenerationDate,
      x => {
        // only when account token is there
        x.token match {
          case a: AccountToken if a.hash.isDefined => Some(a.generationDate)
          case _ => None
        }
      }
    )
    .withFieldComputed(_.acl, _.kind.transformInto[Option[JsonApiAcl]])

  implicit val transformPublicApi: Transformer[ApiAccount, ApiAccountDetails.Public] = {
    transformApiAccountDetails[ApiAccountDetails.Public]
      .withFieldComputed(_.expirationPolicy, _.kind.expirationPolicyKind)
      .buildTransformer
  }

  def transformWithTokenApi(secret: ClearTextSecret): Transformer[ApiAccount, ApiAccountDetails.WithToken] = {
    transformApiAccountDetails[ApiAccountDetails.WithToken]
      .withFieldConst(_.token, secret)
      .withFieldComputed(_.expirationPolicy, _.kind.expirationPolicyKind)
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
    name:              ApiAccountName,                         // used in event log to know who did actions.
    description:       Option[String],
    status:            ApiAccountStatus,
    tenants:           TenantAccessGrant,
    generateToken:     Option[Boolean],
    expirationPolicy:  Option[ApiAccountExpirationPolicyKind], // defaults to "datetime" by default
    expirationDate:    Option[Instant],                        // defaults to the policy default value
    authorizationType: Option[ApiAuthorizationKind],
    acl:               Option[JsonApiAcl]
)

/**
 * This is the object where lies most of the logic that interprets an API account input
 */
object NewRestApiAccount extends ApiAccountCodecs {
  implicit val decoderNewRestApiAccount: JsonDecoder[NewRestApiAccount] = DeriveJsonDecoder.gen

  // build transformer with dependencies
  def transformNewApiAccount(
      d:  Instant,
      id: ApiAccountId,
      t:  AccountToken
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
      .withFieldConst(_.lastAuthenticationDate, None)
      .buildTransformer
  }
}

final case class UpdateApiAccount(
    name:              Option[ApiAccountName],
    description:       Option[String],
    status:            Option[ApiAccountStatus],
    tenants:           Option[TenantAccessGrant],
    expirationPolicy:  Option[ApiAccountExpirationPolicyKind],
    expirationDate:    Option[Instant],
    authorizationType: Option[ApiAuthorizationKind],
    acl:               Option[JsonApiAcl]
)

object UpdateApiAccount extends ApiAccountCodecs {
  implicit val decoderUpdateApiAccount: JsonDecoder[UpdateApiAccount] = DeriveJsonDecoder.gen
}

final case class ApiToken(token: ApiTokenSecret) derives JsonDecoder
object ApiToken {
  given JsonDecoder[ApiTokenSecret] = JsonDecoder[String].map(ApiTokenSecret(_))
}

/**
 * Transformation service for the part with effects / injection
 * of other needed services
 */
class ApiAccountMapping(
    creationDate:   IOResult[Instant],
    generateId:     IOResult[ApiAccountId],
    generateSecret: IOResult[ClearTextSecret],
    createToken:    ClearTextSecret => IOResult[ApiTokenHash]
) extends DateTimeCodecs {
  import ApiAccountExpirationPolicy.*
  import NewRestApiAccount.transformNewApiAccount

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
                  case Some(s) => createAccountToken(s)
                  case None    => creationDate.map(AccountToken(None, _))
                }
      d      <- creationDate
      r      <- transformNewApiAccount(d, id, token).transform(newApiAccount).toIO
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
          .modify(_.description)
          .setToIfDefined(up.description)
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
                  case (NeverExpire, None, None)                                            => NeverExpire
                  case (_, Some(ApiAccountExpirationPolicyKind.Never), _)                   => NeverExpire
                  case (_, _, Some(d))                                                      => ExpireAtDate(d)
                  case (NeverExpire, Some(ApiAccountExpirationPolicyKind.AtDateTime), None) =>
                    ExpireAtDate.now().plusOneMonth
                  case (e: ExpireAtDate, _, None)                                           => e
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
      t <- createAccountToken(s)
    } yield {
      val a = account.modify(_.token).setTo(t)
      (a, s)
    }
  }

  def toDetailsWithSecret(account: ApiAccount, secret: ClearTextSecret): ApiAccountDetails.WithToken = {
    ApiAccountDetails.transformWithTokenApi(secret).transform(account)
  }

  private def createAccountToken(secret: ClearTextSecret): IOResult[AccountToken] = {
    for {
      d <- creationDate
      t <- createToken(secret)
    } yield {
      AccountToken(Some(t), d)
    }
  }
}

object ApiAccountMapping {
  import ApiAccountExpirationPolicy.*

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

  def exp(
      now:  Instant,
      kind: Option[ApiAccountExpirationPolicyKind],
      date: Option[Instant]
  ): ApiAccountExpirationPolicy = {
    kind match {
      case Some(ApiAccountExpirationPolicyKind.Never) => NeverExpire
      case _                                          =>
        date match {
          case Some(d) => ExpireAtDate(d)
          case None    => ExpireAtDate(now).plusOneMonth
        }
    }
  }

  def apiKind(
      a:       ApiAuthorizationKind,
      acl:     Option[List[ApiAclElement]],
      now:     Instant,
      expPol:  Option[ApiAccountExpirationPolicyKind],
      expDate: Option[Instant]
  ): ApiAccountKind.PublicApi = {
    ApiAccountKind.PublicApi(authz(a, acl), exp(now, expPol, expDate))
  }

  def build(
      uuidGen:        StringUuidGenerator,
      tokenGenerator: TokenGenerator
  ) = {
    val getNow         = Instant.now().succeed
    val generateId     = ApiAccountId(uuidGen.newUuid).succeed
    val generateSecret = ApiTokenSecret.generate(tokenGenerator).transformInto[ClearTextSecret].succeed
    def generateToken(secret: ClearTextSecret): IOResult[ApiTokenHash] =
      ApiTokenHash.fromSecret(secret.transformInto[ApiTokenSecret]).succeed

    new ApiAccountMapping(getNow, generateId, generateSecret, generateToken)
  }
}
