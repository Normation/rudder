/*
 * Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bootstrap.liftweb;

import com.normation.rudder.domain.logger.ApplicationLogger;
import com.normation.rudder.users.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.List;

import com.normation.JZioRuntime;


/**
 * This is a copy of spring ProviderManager that changes the following things:
 * - the list of provider is a Rudder list of provider (with their name, config, etc)
 * - it dynamically obtained (ie, even if a plugin add some providers after construction)
 * - provider are tested for enablement (license ok or whatever)
 */
public class RudderProviderManager implements org.springframework.security.authentication.AuthenticationManager, MessageSourceAware,
		InitializingBean {
	// ~ Static fields/initializers
	// =====================================================================================

	private static final Log logger = LogFactory.getLog("org.springframework.security.RudderProviderManager");

	// ~ Instance fields
	// ================================================================================================

	private AuthenticationEventPublisher eventPublisher = new NullEventPublisher();
	protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();
	private AuthenticationManager parent;
	private boolean eraseCredentialsAfterAuthentication = true;

    // storing user and their sessions
    private UserRepository userRepository;

    // the rudder provider
    private DynamicRudderProviderManager dynamicProvider;

	public RudderProviderManager(DynamicRudderProviderManager dynamicProvider, UserRepository userRepository) {
	    this.dynamicProvider = dynamicProvider;
        this.userRepository = userRepository;
	}

	// ~ Methods
	// ========================================================================================================

	public void afterPropertiesSet() throws Exception {
	}


	/**
	 * Attempts to authenticate the passed {@link Authentication} object.
	 * <p>
	 * The list of {@link AuthenticationProvider}s will be successively tried until an
	 * <code>AuthenticationProvider</code> indicates it is capable of authenticating the
	 * type of <code>Authentication</code> object passed. Authentication will then be
	 * attempted with that <code>AuthenticationProvider</code>.
	 * <p>
	 * If more than one <code>AuthenticationProvider</code> supports the passed
	 * <code>Authentication</code> object, the first one able to successfully
	 * authenticate the <code>Authentication</code> object determines the
	 * <code>result</code>, overriding any possible <code>AuthenticationException</code>
	 * thrown by earlier supporting <code>AuthenticationProvider</code>s.
	 * On successful authentication, no subsequent <code>AuthenticationProvider</code>s
	 * will be tried.
	 * If authentication was not successful by any supporting
	 * <code>AuthenticationProvider</code> the last thrown
	 * <code>AuthenticationException</code> will be rethrown.
	 *
	 * @param authentication the authentication request object.
	 *
	 * @return a fully authenticated object including credentials.
	 *
	 * @throws AuthenticationException if authentication fails.
	 */
	public Authentication authenticate(Authentication authentication)
			throws AuthenticationException {
		Class<? extends Authentication> toTest = authentication.getClass();
		AuthenticationException lastException = null;
		Authentication result = null;
		boolean debug = logger.isDebugEnabled();

		for (AuthenticationProvider provider : getProviders()) {
			if (!provider.supports(toTest)) {
				continue;
			}

			if (debug) {
				logger.debug("Rudder authentication attempt using "
						+ provider.getClass().getName());
			}

			try {
				result = provider.authenticate(authentication);

				if (result != null) {
					copyDetails(authentication, result);
					break;
				}
			}
			catch (AccountStatusException e) {
				prepareException(e, authentication);
				// SEC-546: Avoid polling additional providers if auth failure is due to
				// invalid account status
				throw cleanException(e);
			}
			catch (InternalAuthenticationServiceException e) {
				prepareException(e, authentication);
				throw cleanException(e);
			}
			catch (AuthenticationException e) {
				lastException = e;
			}
            finally {
                if(provider instanceof RudderAuthenticationProvider) {
                    RudderAuthenticationProvider p = (RudderAuthenticationProvider) provider;
                    String principal = "unknown";
                    if(authentication.getPrincipal() != null) {
                        principal = authentication.getPrincipal().toString();
                    }

                    Boolean authenticated = false;


                    if(result != null) {
                        authenticated = result.isAuthenticated();
                        if(result.getPrincipal() != null && result.getPrincipal() instanceof RudderUserDetail) {
                            RudderUserDetail details = ((RudderUserDetail) result.getPrincipal());
                            principal = details.getUsername();

                            if(authenticated) {
                                // create the session in base if authenticated
                                // sessions id is available in the "result.details" part for web auth... It's really a guessing
                                // game, spring is not helping
                                String sessionId = null;
                                if(result.getDetails() instanceof WebAuthenticationDetails) {
                                  sessionId = ((WebAuthenticationDetails)result.getDetails()).getSessionId();
                                } else {
                                  final String className = result.getDetails().getClass().getName();
                                  ApplicationLogger.warn(() -> "Rudder does not know how to get sessionId from '"+className+"'. Please report to developers that message");
                                  sessionId = Integer.toHexString(result.getDetails().hashCode());
                                }
                                JZioRuntime.runNow(userRepository.logStartSession(details.getUsername(), details.roles().map(x -> x.name()).toList(), com.normation.rudder.users.SessionId.apply(sessionId), p.name(), org.joda.time.DateTime.now()));
                            }
                        }
                    }


                    String msg = "Rudder authentication attempt for principal '" +principal+
                            "' with backend '"+p.name()+"': " + (authenticated? "success":"failure");

                    // we don't want to log info about "rootAdmin" backend
                    if(p.name() == "rootAdmin") {
                        logger.debug(msg);
                    } else {
                        ApplicationLogger.info(() -> msg);
                    }
                }
            }
		}

		if (result == null && parent != null) {
			// Allow the parent to try.
			try {
				result = parent.authenticate(authentication);
			}
			catch (ProviderNotFoundException e) {
				// ignore as we will throw below if no other exception occurred prior to
				// calling parent and the parent
				// may throw ProviderNotFound even though a provider in the child already
				// handled the request
			}
			catch (AuthenticationException e) {
				lastException = e;
			}
		}

		if (result != null) {
			if (eraseCredentialsAfterAuthentication
					&& (result instanceof CredentialsContainer)) {
				// Authentication is complete. Remove credentials and other secret data
				// from authentication
				((CredentialsContainer) result).eraseCredentials();
			}

			eventPublisher.publishAuthenticationSuccess(result);
			return result;
		}

		// Parent was null, or didn't authenticate (or throw an exception).

		if (lastException == null) {
			lastException = new ProviderNotFoundException(messages.getMessage(
					"ProviderManager.providerNotFound",
					new Object[] { toTest.getName() },
					"Rudder Authentication: no AuthenticationProvider found for {0}"));
		}

		prepareException(lastException, authentication);

        //throw authentication exception without the stack trace.
        throw cleanException(lastException);
	}


	// a version of the exception is thrown without stack trace to avoid having pages and pages of exception
    // in logs. Only keep information if debug mode is enabled.
	private AuthenticationException cleanException(AuthenticationException t) {
        if(ApplicationLogger.isDebugEnabled()) {
            return t;
        } else {
            return new AuthenticationException(t.getMessage(), t.getCause()) {
                @Override
                public synchronized Throwable fillInStackTrace() {
                    return this;
                }
            };
        }
    }


	@SuppressWarnings("deprecation")
	private void prepareException(AuthenticationException ex, Authentication auth) {
		eventPublisher.publishAuthenticationFailure(ex, auth);
	}

	/**
	 * Copies the authentication details from a source Authentication object to a
	 * destination one, provided the latter does not already have one set.
	 *
	 * @param source source authentication
	 * @param dest the destination authentication object
	 */
	private void copyDetails(Authentication source, Authentication dest) {
		if ((dest instanceof AbstractAuthenticationToken) && (dest.getDetails() == null)) {
			AbstractAuthenticationToken token = (AbstractAuthenticationToken) dest;

			token.setDetails(source.getDetails());
		}
	}

	public List<AuthenticationProvider> getProviders() {
		return Arrays.asList(dynamicProvider.getEnabledProviders());
	}

	public void setMessageSource(MessageSource messageSource) {
		this.messages = new MessageSourceAccessor(messageSource);
	}

	public void setAuthenticationEventPublisher(
			AuthenticationEventPublisher eventPublisher) {
		Assert.notNull(eventPublisher, "AuthenticationEventPublisher cannot be null");
		this.eventPublisher = eventPublisher;
	}

	/**
	 * If set to, a resulting {@code Authentication} which implements the
	 * {@code CredentialsContainer} interface will have its
	 * {@link CredentialsContainer#eraseCredentials() eraseCredentials} method called
	 * before it is returned from the {@code authenticate()} method.
	 *
	 * @param eraseSecretData set to {@literal false} to retain the credentials data in
	 * memory. Defaults to {@literal true}.
	 */
	public void setEraseCredentialsAfterAuthentication(boolean eraseSecretData) {
		this.eraseCredentialsAfterAuthentication = eraseSecretData;
	}

	public boolean isEraseCredentialsAfterAuthentication() {
		return eraseCredentialsAfterAuthentication;
	}

	private static final class NullEventPublisher implements AuthenticationEventPublisher {
		public void publishAuthenticationFailure(AuthenticationException exception,
				Authentication authentication) {
		}

		public void publishAuthenticationSuccess(Authentication authentication) {
		}
	}
}
