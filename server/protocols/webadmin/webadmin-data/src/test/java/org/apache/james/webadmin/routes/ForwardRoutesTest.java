/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.webadmin.routes;

import static com.jayway.restassured.RestAssured.when;
import static com.jayway.restassured.RestAssured.with;
import static org.apache.james.webadmin.Constants.SEPARATOR;
import static org.apache.james.webadmin.WebAdminServer.NO_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.DefaultConfigurationBuilder;
import org.apache.james.core.Domain;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.domainlist.memory.MemoryDomainList;
import org.apache.james.metrics.logger.DefaultMetricFactory;
import org.apache.james.rrt.api.RecipientRewriteTable;
import org.apache.james.rrt.api.RecipientRewriteTableException;
import org.apache.james.rrt.memory.MemoryRecipientRewriteTable;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.memory.MemoryUsersRepository;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.filter.log.LogDetail;
import com.jayway.restassured.http.ContentType;

class ForwardRoutesTest {

    private static final Domain DOMAIN = Domain.of("b.com");
    public static final String CEDRIC = "cedric@" + DOMAIN.name();
    public static final String ALICE = "alice@" + DOMAIN.name();
    public static final String ALICE_WITH_SLASH = "alice/@" + DOMAIN.name();
    public static final String ALICE_WITH_ENCODED_SLASH = "alice%2F@" + DOMAIN.name();
    public static final String BOB = "bob@" + DOMAIN.name();
    public static final String BOB_PASSWORD = "123456";
    public static final String ALICE_PASSWORD = "789123";
    public static final String ALICE_SLASH_PASSWORD = "abcdef";
    public static final String CEDRIC_PASSWORD = "456789";

    private WebAdminServer webAdminServer;

    private void createServer(ForwardRoutes forwardRoutes) throws Exception {
        webAdminServer = WebAdminUtils.createWebAdminServer(
            new DefaultMetricFactory(),
            forwardRoutes);
        webAdminServer.configure(NO_CONFIGURATION);
        webAdminServer.await();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .setBasePath("address/forwards")
            .log(LogDetail.METHOD)
            .build();
    }

    @AfterEach
    void stop() {
        webAdminServer.destroy();
    }

    @Nested
    class NormalBehaviour {

        MemoryUsersRepository usersRepository;
        MemoryDomainList domainList;
        MemoryRecipientRewriteTable memoryRecipientRewriteTable;

        @BeforeEach
        void setUp() throws Exception {
            memoryRecipientRewriteTable = new MemoryRecipientRewriteTable();
            DNSService dnsService = mock(DNSService.class);
            domainList = new MemoryDomainList(dnsService);
            domainList.setAutoDetectIP(false);
            domainList.setAutoDetect(false);
            domainList.configure(new DefaultConfigurationBuilder());
            domainList.addDomain(DOMAIN);

            usersRepository = MemoryUsersRepository.withVirtualHosting();
            usersRepository.setDomainList(domainList);
            usersRepository.configure(new DefaultConfigurationBuilder());

            usersRepository.addUser(BOB, BOB_PASSWORD);
            usersRepository.addUser(ALICE, ALICE_PASSWORD);
            usersRepository.addUser(ALICE_WITH_SLASH, ALICE_SLASH_PASSWORD);
            usersRepository.addUser(CEDRIC, CEDRIC_PASSWORD);

            createServer(new ForwardRoutes(memoryRecipientRewriteTable, usersRepository, new JsonTransformer()));
        }

        @Test
        void getForwardShouldBeEmpty() {
            when()
                .get()
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body(is("[]"));
        }

        @Test
        void getForwardShouldListExistingForwardsInAlphabeticOrder() {
            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            with()
                .put(CEDRIC + SEPARATOR + "targets" + SEPARATOR + BOB);

            List<String> addresses =
                when()
                    .get()
                .then()
                    .contentType(ContentType.JSON)
                    .statusCode(HttpStatus.OK_200)
                    .extract()
                    .body()
                    .jsonPath()
                    .getList(".");
            assertThat(addresses).containsExactly(ALICE, CEDRIC);
        }

        @Test
        void getNotRegisteredForwardShouldReturnNotFound() {
            Map<String, Object> errors = when()
                .get("unknown@domain.travel")
            .then()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getMap(".");

            assertThat(errors)
                .containsEntry("statusCode", HttpStatus.NOT_FOUND_404)
                .containsEntry("type", "InvalidArgument")
                .containsEntry("message", "The forward does not exist");
        }

        @Test
        void putUserInForwardShouldReturnCreated() {
            when()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.CREATED_201);
        }

        @Test
        void putUserWithSlashInForwardShouldReturnCreated() {
            when()
                .put(BOB + SEPARATOR + "targets" + SEPARATOR + ALICE_WITH_ENCODED_SLASH)
            .then()
                .statusCode(HttpStatus.CREATED_201);
        }

        @Test
        void putUserWithSlashInForwardShouldAddItAsADestination() {
            with()
                .put(BOB + SEPARATOR + "targets" + SEPARATOR + ALICE_WITH_ENCODED_SLASH);

            when()
                .get(BOB)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(ALICE_WITH_SLASH));
        }

        @Test
        void putUserInForwardShouldCreateForward() {
            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            when()
                .get(ALICE)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(BOB));
        }

        @Test
        void putUserInForwardWithEncodedSlashShouldReturnCreated() {
            when()
                .put(ALICE_WITH_ENCODED_SLASH + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.CREATED_201);
        }

        @Test
        void putUserInForwardWithEncodedSlashShouldCreateForward() {
            with()
                .put(ALICE_WITH_ENCODED_SLASH + SEPARATOR + "targets" + SEPARATOR + BOB);

            when()
                .get(ALICE_WITH_ENCODED_SLASH)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(BOB));
        }

        @Test
        void putSameUserInForwardTwiceShouldBeIdempotent() {
            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            when()
                .get(ALICE)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(BOB));
        }

        @Test
        void putUserInForwardShouldAllowSeveralDestinations() {
            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + CEDRIC);

            when()
                .get(ALICE)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(BOB, CEDRIC));
        }

        @Test
        void forwardShouldAllowIdentity() {
            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + ALICE);

            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + CEDRIC);

            when()
                .get(ALICE)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(ALICE, CEDRIC));
        }

        @Test
        void putUserInForwardShouldRequireExistingBaseUser() {
            Map<String, Object> errors = when()
                .put("notFound@" + DOMAIN.name() + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getMap(".");

            assertThat(errors)
                .containsEntry("statusCode", HttpStatus.NOT_FOUND_404)
                .containsEntry("type", "InvalidArgument")
                .containsEntry("message", "Requested base forward address does not correspond to a user");
        }

        @Test
        void getForwardShouldReturnMembersInAlphabeticOrder() {
            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + CEDRIC);

            when()
                .get(ALICE)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(BOB, CEDRIC));
        }

        @Test
        void forwardShouldAcceptExternalAddresses() {
            String externalAddress = "external@other.com";

            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + externalAddress);

            when()
                .get(ALICE)
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body("mailAddress", hasItems(externalAddress));
        }

        @Test
        void deleteUserNotInForwardShouldReturnOK() {
            when()
                .delete(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.OK_200);
        }

        @Test
        void deleteLastUserInForwardShouldDeleteForward() {
            with()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            with()
                .delete(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB);

            when()
                .get()
            .then()
                .contentType(ContentType.JSON)
                .statusCode(HttpStatus.OK_200)
                .body(is("[]"));
        }
    }

    @Nested
    class FilteringOtherRewriteRuleTypes extends NormalBehaviour {

        @BeforeEach
        void setup() throws Exception {
            super.setUp();
            memoryRecipientRewriteTable.addErrorMapping("error", DOMAIN, "disabled");
            memoryRecipientRewriteTable.addRegexMapping("regex", DOMAIN, ".*@b\\.com");
            memoryRecipientRewriteTable.addAliasDomainMapping(Domain.of("alias"), DOMAIN);
        }

    }

    @Nested
    class ExceptionHandling {

        private RecipientRewriteTable memoryRecipientRewriteTable;

        @BeforeEach
        void setUp() throws Exception {
            memoryRecipientRewriteTable = mock(RecipientRewriteTable.class);
            UsersRepository userRepository = mock(UsersRepository.class);
            Mockito.when(userRepository.contains(eq(ALICE))).thenReturn(true);
            DomainList domainList = mock(DomainList.class);
            Mockito.when(domainList.containsDomain(any())).thenReturn(true);
            createServer(new ForwardRoutes(memoryRecipientRewriteTable, userRepository, new JsonTransformer()));
        }

        @Test
        void getMalformedForwardShouldReturnBadRequest() {
            Map<String, Object> errors = when()
                .get("not-an-address")
            .then()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getMap(".");

            assertThat(errors)
                .containsEntry("statusCode", HttpStatus.BAD_REQUEST_400)
                .containsEntry("type", "InvalidArgument")
                .containsEntry("message", "The forward is not an email address")
                .containsEntry("cause", "Out of data at position 1 in 'not-an-address'");
        }

        @Test
        void putMalformedForwardShouldReturnBadRequest() {
            Map<String, Object> errors = when()
                .put("not-an-address" + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getMap(".");

            assertThat(errors)
                .containsEntry("statusCode", HttpStatus.BAD_REQUEST_400)
                .containsEntry("type", "InvalidArgument")
                .containsEntry("message", "The forward is not an email address")
                .containsEntry("cause", "Out of data at position 1 in 'not-an-address'");
        }

        @Test
        void putUserInForwardWithSlashShouldReturnNotFound() {
            when()
                .put(ALICE_WITH_SLASH + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .body(containsString("404 Not found"));
        }

        @Test
        void putUserWithSlashInForwardShouldReturnNotFound() {
            when()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + ALICE_WITH_SLASH)
            .then()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .body(containsString("404 Not found"));
        }

        @Test
        void putMalformedAddressShouldReturnBadRequest() {
            Map<String, Object> errors = when()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + "not-an-address")
            .then()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getMap(".");

            assertThat(errors)
                .containsEntry("statusCode", HttpStatus.BAD_REQUEST_400)
                .containsEntry("type", "InvalidArgument")
                .containsEntry("message", "The forward is not an email address")
                .containsEntry("cause", "Out of data at position 1 in 'not-an-address'");
        }

        @Test
        void putRequiresTwoPathParams() {
            when()
                .put(ALICE)
            .then()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .body("statusCode", is(400))
                .body("type", is("InvalidArgument"))
                .body("message", is("A destination address needs to be specified in the path"));
        }

        @Test
        void deleteMalformedForwardShouldReturnBadRequest() {
            Map<String, Object> errors = when()
                .delete("not-an-address" + SEPARATOR + "targets" + SEPARATOR + ALICE)
            .then()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getMap(".");

            assertThat(errors)
                .containsEntry("statusCode", HttpStatus.BAD_REQUEST_400)
                .containsEntry("type", "InvalidArgument")
                .containsEntry("message", "The forward is not an email address")
                .containsEntry("cause", "Out of data at position 1 in 'not-an-address'");
        }

        @Test
        void deleteMalformedAddressShouldReturnBadRequest() {
            Map<String, Object> errors = when()
                .delete(ALICE + SEPARATOR + "targets" + SEPARATOR + "not-an-address")
            .then()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getMap(".");

            assertThat(errors)
                .containsEntry("statusCode", HttpStatus.BAD_REQUEST_400)
                .containsEntry("type", "InvalidArgument")
                .containsEntry("message", "The forward is not an email address")
                .containsEntry("cause", "Out of data at position 1 in 'not-an-address'");
        }

        @Test
        void deleteRequiresTwoPathParams() {
            when()
                .delete(ALICE)
            .then()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .body("statusCode", is(400))
                .body("type", is("InvalidArgument"))
                .body("message", is("A destination address needs to be specified in the path"));
        }

        @Test
        void putShouldReturnErrorWhenRecipientRewriteTableExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTableException.class)
                .when(memoryRecipientRewriteTable)
                .addForwardMapping(anyString(), any(), anyString());

            when()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void putShouldReturnErrorWhenErrorMappingExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTable.ErrorMappingException.class)
                .when(memoryRecipientRewriteTable)
                .addForwardMapping(anyString(), any(), anyString());

            when()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void putShouldReturnErrorWhenRuntimeExceptionIsThrown() throws Exception {
            doThrow(RuntimeException.class)
                .when(memoryRecipientRewriteTable)
                .addForwardMapping(anyString(), any(), anyString());

            when()
                .put(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void getAllShouldReturnErrorWhenRecipientRewriteTableExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTableException.class)
                .when(memoryRecipientRewriteTable)
                .getAllMappings();

            when()
                .get()
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void getAllShouldReturnErrorWhenErrorMappingExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTable.ErrorMappingException.class)
                .when(memoryRecipientRewriteTable)
                .getAllMappings();

            when()
                .get()
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void getAllShouldReturnErrorWhenRuntimeExceptionIsThrown() throws Exception {
            doThrow(RuntimeException.class)
                .when(memoryRecipientRewriteTable)
                .getAllMappings();

            when()
                .get()
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void deleteShouldReturnErrorWhenRecipientRewriteTableExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTableException.class)
                .when(memoryRecipientRewriteTable)
                .removeForwardMapping(anyString(), any(), anyString());

            when()
                .delete(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void deleteShouldReturnErrorWhenErrorMappingExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTable.ErrorMappingException.class)
                .when(memoryRecipientRewriteTable)
                .removeForwardMapping(anyString(), any(), anyString());

            when()
                .delete(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void deleteShouldReturnErrorWhenRuntimeExceptionIsThrown() throws Exception {
            doThrow(RuntimeException.class)
                .when(memoryRecipientRewriteTable)
                .removeForwardMapping(anyString(), any(), anyString());

            when()
                .delete(ALICE + SEPARATOR + "targets" + SEPARATOR + BOB)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void getShouldReturnErrorWhenRecipientRewriteTableExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTableException.class)
                .when(memoryRecipientRewriteTable)
                .getMappings(anyString(), any());

            when()
                .get(ALICE)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void getShouldReturnErrorWhenErrorMappingExceptionIsThrown() throws Exception {
            doThrow(RecipientRewriteTable.ErrorMappingException.class)
                .when(memoryRecipientRewriteTable)
                .getMappings(anyString(), any());

            when()
                .get(ALICE)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }

        @Test
        void getShouldReturnErrorWhenRuntimeExceptionIsThrown() throws Exception {
            doThrow(RuntimeException.class)
                .when(memoryRecipientRewriteTable)
                .getMappings(anyString(), any());

            when()
                .get(ALICE)
            .then()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .body(containsString("500 Internal Server Error"));
        }
    }

}