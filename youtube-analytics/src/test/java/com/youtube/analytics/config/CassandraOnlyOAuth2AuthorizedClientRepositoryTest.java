package com.youtube.analytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CassandraOnlyOAuth2AuthorizedClientRepositoryTest {

    @Test
    void authenticatedLoadsAreDelegatedDirectlyToCassandraService() {
        OAuth2AuthorizedClientService service = mock(OAuth2AuthorizedClientService.class);
        CassandraOnlyOAuth2AuthorizedClientRepository repository =
                new CassandraOnlyOAuth2AuthorizedClientRepository(service);
        TestingAuthenticationToken principal = new TestingAuthenticationToken(
                "google-subject", "unused", AuthorityUtils.createAuthorityList("ROLE_USER"));

        assertThat((Object) repository.loadAuthorizedClient("google", principal, null)).isNull();
        verify(service).loadAuthorizedClient("google", "google-subject");
    }

    @Test
    void anonymousLoadsDoNotFallBackToAnHttpSessionTokenStore() {
        OAuth2AuthorizedClientService service = mock(OAuth2AuthorizedClientService.class);
        CassandraOnlyOAuth2AuthorizedClientRepository repository =
                new CassandraOnlyOAuth2AuthorizedClientRepository(service);
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat((Object) repository.loadAuthorizedClient("google", anonymous, null)).isNull();
        verifyNoInteractions(service);
    }
}
