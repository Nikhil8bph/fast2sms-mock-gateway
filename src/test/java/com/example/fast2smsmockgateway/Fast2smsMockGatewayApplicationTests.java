package com.example.fast2smsmockgateway;

import com.example.fast2smsmockgateway.events.EmailDeliveryWorker;
import com.example.fast2smsmockgateway.security.ApiKeyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sandbox;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "EMAIL_FROM=sender@example.test",
        "EMAIL_TO=inbox@example.test",
        "GMAIL_APP_PASSWORD=example",
        "FAST2SMS_API_KEYS=primary-sandbox-key,secondary-sandbox-key",
        "sandbox.outbox-delay-ms=3600000"
})
class Fast2smsMockGatewayApplicationTests {
    private static final String KEY = "primary-sandbox-key";

    @Autowired WebApplicationContext context;
    @Autowired ApiKeyFilter apiKeyFilter;
    @Autowired EmailDeliveryWorker worker;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @MockitoBean JavaMailSender mail;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM sandbox_api_call");
        jdbc.update("DELETE FROM sandbox_otp_challenge");
        jdbc.update("DELETE FROM sandbox_resource");
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(apiKeyFilter).build();
    }

    @Test
    void authenticatesAQuickSmsAndReportsPersistedEmailDelivery() throws Exception {
        mvc.perform(get("/dev/bulkV2").header("Authorization", "Bearer " + KEY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status_code").value(412));
        String result = mvc.perform(get("/dev/bulkV2").header("Authorization", KEY)
                        .param("route", "q").param("message", "Sandbox smoke test").param("numbers", "9999999999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return").value(true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).get("request_id").asString();

        worker.deliver();

        verify(mail).send(argThat((SimpleMailMessage message) ->
                message.getSubject().contains(id)
                        && "sender@example.test".equals(message.getFrom())
                        && message.getTo().length == 1
                        && "inbox@example.test".equals(message.getTo()[0])
                        && message.getText().contains("Sandbox smoke test")));
        mvc.perform(get("/dev/dlr/" + id).header("Authorization", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].delivery_status[0].status").value("Sent"));
        mvc.perform(get("/dev/dlr/" + id).header("Authorization", "secondary-sandbox-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptsFormSmsAndBothWhatsAppPayloadFormats() throws Exception {
        mvc.perform(post("/dev/bulkV2").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("route=q&numbers=9999999999&message=Form+example"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return").value(true));
        mvc.perform(post("/dev/whatsapp-session").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"phone_number_id":"100000000000002","to":"9999999999","text":"Session text"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return").value(true));
        mvc.perform(post("/dev/whatsapp/v26.0/100000000000002/messages").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"messaging_product":"whatsapp","to":"919999999999","type":"template",
                                 "template":{"name":"demo","language":{"code":"en"}}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].id").exists());
    }

    @Test
    void groupsScheduledBatchItemsUnderOneRequestId() throws Exception {
        long schedule = java.time.Instant.now().plusSeconds(3600).getEpochSecond();
        String result = mvc.perform(post("/dev/custom").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"route":"q","requests":[
                                  {"numbers":"9999999991","message":"Immediate"},
                                  {"numbers":"9999999992","message":"Later","schedule_time":%s}
                                ]}
                                """.formatted(schedule)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).get("request_id").asString();

        worker.deliver();

        verify(mail).send(argThat((SimpleMailMessage message) ->
                message.getSubject().contains(id) && message.getText().contains("Immediate")
                        && !message.getText().contains("Later")));
        mvc.perform(get("/dev/dlr/" + id).header("Authorization", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].delivery_status.length()").value(2))
                .andExpect(jsonPath("$.data[0].delivery_status[?(@.mobile == 9999999991)].status").value(contains("Sent")))
                .andExpect(jsonPath("$.data[0].delivery_status[?(@.mobile == 9999999992)].status").value(contains("Pending")));
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        mvc.perform(get("/dev/sms_logs").header("Authorization", KEY).param("from", today).param("to", today))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pagination.total").exists());
    }

    @Test
    void persistsScopedWhatsAppTemplatesAndMedia() throws Exception {
        String base = "/dev/whatsapp/v26.0/";
        String templates = base + "100000000000001/message_templates";
        String response = mvc.perform(post(templates).header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"test_template","language":"en","category":"UTILITY",
                                 "components":[{"type":"BODY","text":"Testing"}]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).get("id").asString();
        mvc.perform(get(base + id).header("Authorization", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("test_template"));
        mvc.perform(get(base + id).header("Authorization", "secondary-sandbox-key"))
                .andExpect(status().isNotFound());

        String mediaPath = base + "100000000000002/media";
        var file = new org.springframework.mock.web.MockMultipartFile("file", "fixture.txt", "text/plain",
                new byte[]{97, 98});
        String uploaded = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(mediaPath)
                        .file(file).header("Authorization", KEY).param("messaging_product", "whatsapp").param("type", "text/plain"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String mediaId = json.readTree(uploaded).get("id").asString();
        mvc.perform(get(mediaPath + "/" + mediaId).header("Authorization", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.file_size").value(2));
        mvc.perform(get(mediaPath + "/" + mediaId + "/file").header("Authorization", KEY))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(new byte[]{97, 98}));
    }


    @Test
    void storesAndConsumesAnOtp() throws Exception {
        String mobile = "9999999988";
        mvc.perform(post("/dev/otp/send").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp_id", "demo")
                        .param("otp_expiry", "1").param("otp", "001234"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request_id").exists());
        mvc.perform(post("/dev/otp/resend").header("Authorization", KEY).param("mobile", mobile))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(startsWith("Please wait ")));

        java.time.Instant now = java.time.Instant.now();
        java.sql.Timestamp originalExpiry = java.sql.Timestamp.from(now.plusSeconds(30));
        jdbc.update("UPDATE sandbox_otp_challenge SET last_sent_at = ?, expires_at = ? WHERE id LIKE ?",
                java.sql.Timestamp.from(now.minusSeconds(30)), originalExpiry, "%:" + mobile);
        mvc.perform(post("/dev/otp/resend").header("Authorization", KEY).param("mobile", mobile))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request_id").exists());
        assertThat(jdbc.queryForObject("SELECT expires_at FROM sandbox_otp_challenge WHERE id LIKE ?",
                java.sql.Timestamp.class, "%:" + mobile)).isAfter(originalExpiry);

        mvc.perform(post("/dev/otp/verify").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp", "123456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid OTP"));
        mvc.perform(post("/dev/otp/verify").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp", "001234"))
                .andExpect(status().isOk());
        mvc.perform(post("/dev/otp/verify").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp", "001234"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("OTP not found or already verified"));
    }

    @Test
    void mapsEveryDocumentedMethodAndPath() throws Exception {
        String wa = "/dev/whatsapp/v26.0/100000000000002";
        var routes = List.of(
                "GET /dev/bulkV2", "POST /dev/bulkV2", "POST /dev/custom",
                "GET /dev/dlt_manager", "POST /dev/dlt_manager/add_entity",
                "POST /dev/dlt_manager/add_sender", "POST /dev/dlt_manager/add_sender_excel",
                "POST /dev/dlt_manager/add_template", "POST /dev/dlt_manager/add_template_excel",
                "GET /dev/dlr/S123", "GET /dev/sms_logs", "GET /dev/sms_summary",
                "POST /dev/otp/send", "POST /dev/otp/verify", "POST /dev/otp/resend",
                "GET /dev/dlt_manager/whatsapp", "GET " + wa, "GET " + wa + "/phone_numbers",
                "GET " + wa + "/whatsapp_business_profile", "POST " + wa + "/whatsapp_business_profile",
                "GET /dev/whatsapp", "POST /dev/whatsapp-session", "POST " + wa + "/messages",
                "GET " + wa + "/message_templates", "POST " + wa + "/message_templates",
                "DELETE " + wa + "/message_templates", "POST " + wa,
                "GET " + wa + "/media/123", "GET " + wa + "/media/123/file",
                "POST " + wa + "/media", "POST " + wa + "/media_handle",
                "GET " + wa + "/message_qrdls", "GET " + wa + "/message_qrdls/123",
                "POST " + wa + "/message_qrdls", "POST " + wa + "/message_qrdls/123",
                "GET " + wa + "/message_qrdls/123/image.png",
                "DELETE " + wa + "/message_qrdls/123", "GET " + wa + "/block_users",
                "POST " + wa + "/block_users", "DELETE " + wa + "/block_users",
                "GET /dev/whatsapp/W123", "GET /dev/whatsapp_logs", "GET /dev/whatsapp_summary",
                "POST /dev/rcs", "POST /dev/voice/bulk", "GET /dev/voice/files", "GET /dev/voice/report",
                "POST /dev/wallet");
        for (String route : routes) {
            assertRoute(route);
        }
        for (String service : List.of("sms", "otp", "whatsapp", "rcs", "voice")) {
            String base = "/dev/webhook/v2/" + service;
            for (String route : List.of("GET " + base, "POST " + base, "PUT " + base + "/wh_123",
                    "DELETE " + base + "/wh_123")) {
                assertRoute(route);
            }
        }
    }

    private void assertRoute(String route) throws Exception {
        String[] parts = route.split(" ", 2);
        var request = new MockHttpServletRequest(parts[0], parts[1]);
        if (parts[1].endsWith("/media") || parts[1].endsWith("/media_handle")) {
            request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE);
        } else {
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        ServletRequestPathUtils.parseAndCache(request);
        assertThat(mappings.getHandler(request)).as(route).isNotNull();
    }

    @Test
    void acceptsEachRcsTypeAndQueuesAnEmail() throws Exception {
        var payloads = List.of(
                "\"type\":\"plain\",\"message\":\"RCS text\"",
                "\"type\":\"rich\",\"title\":\"Sale\",\"message\":\"RCS rich\"",
                "\"type\":\"media\",\"media_url\":\"https://example.test/image.png\"",
                """
                "type":"carousel","cards":[
                  {"title":"First","description":"Card one"},
                  {"title":"Second","description":"Card two","suggestion_type":"openUrl",
                   "suggestion_text":"Open","suggestion_url":"https://example.test/product"}]
                """);
        for (String payload : payloads) {
            mvc.perform(post("/dev/rcs").header("Authorization", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bot_id\":\"1001\",\"to\":\"9999999999\"," + payload + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.request_id").value(matchesPattern("R[A-Za-z0-9]{14}")));
        }
        mvc.perform(get("/dev/rcs").header("Authorization", KEY).param("bot_id", "1001")
                        .param("to", "9999999999").param("type", "plain").param("message", "RCS GET"))
                .andExpect(status().isOk());
        mvc.perform(get("/dev/rcs").header("Authorization", KEY)
                        .param("bot_id", "1001").param("to", "9999999999").param("type", "carousel"))
                .andExpect(status().isBadRequest());

        worker.deliver();
        verify(mail, times(5)).send(argThat((SimpleMailMessage message) -> message.getSubject().contains("RCS")));
    }

    @Test
    void returnsVoiceFilesAndAnIdempotentVoiceCampaignReport() throws Exception {
        mvc.perform(get("/dev/voice/files").header("Authorization", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.files.length()").value(3));
        String request = """
                {"file_id":"30031","dtmf_keys":"1,2","campaign_name":"Sandbox demo",
                 "numbers":"9999999999;09999999999 bad 919999999998","idempotency_key":"voice-test"}
                """;
        String first = mvc.perform(post("/dev/voice/bulk").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(matchesPattern("V[A-Za-z0-9]{14}")))
                .andExpect(jsonPath("$.numbers").value(2)).andExpect(jsonPath("$.removed").value(2))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/dev/voice/bulk").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(first).get("request_id").asString();
        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        mvc.perform(get("/dev/voice/report").header("Authorization", KEY)
                        .param("request_id", id).param("numbers", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.data[0].status").value("queued"));

        worker.deliver();
        verify(mail).send(argThat((SimpleMailMessage message) -> message.getSubject().contains(id)));
        mvc.perform(get("/dev/voice/report").header("Authorization", KEY)
                        .param("request_id", id).param("numbers", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("dtmf"))
                .andExpect(jsonPath("$.completed").value(true)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/dev/voice/report").header("Authorization", "secondary-sandbox-key").param("request_id", id))
                .andExpect(status().isNotFound());
        mvc.perform(post("/dev/voice/bulk").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"numbers\":\"9999999999\"}"))
                .andExpect(status().is(422));
    }

    @Test
    void usesTheWebhookEnvelopeAndPutReplacementForAllServices() throws Exception {
        for (String service : List.of("sms", "otp", "whatsapp", "rcs", "voice")) {
            String base = "/dev/webhook/v2/" + service;
            String response = mvc.perform(post(base).header("Authorization", KEY).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Sandbox","url":"https://example.test/hook","method":"PUT","event":"failed"}
                                    """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.return").value(true))
                    .andExpect(jsonPath("$.data.service").value(service)).andReturn().getResponse().getContentAsString();
            String id = json.readTree(response).get("data").get("id").asString();
            mvc.perform(put(base + "/" + id).header("Authorization", KEY).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Updated","url":"https://example.test/new"}
                                    """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.method").value("POST"))
                    .andExpect(jsonPath("$.data.event").value("all"));
            mvc.perform(get(base).header("Authorization", KEY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id));
            mvc.perform(delete(base + "/" + id).header("Authorization", KEY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.return").value(true));
            mvc.perform(get(base).header("Authorization", KEY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Test
    void filtersWhatsAppPhonesAndGeneratesAnAuthenticatedQrImage() throws Exception {
        String phoneUrl = "/dev/whatsapp/v26.0/100000000000001/phone_numbers";
        mvc.perform(get(phoneUrl).header("Authorization", KEY).param("fields", "id,verified_name")
                        .param("filtering", "[{'field':'account_mode','operator':'EQUAL','value':'SANDBOX'}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].verified_name").value("Sandbox Business"))
                .andExpect(jsonPath("$.data[0].quality_rating").doesNotExist());
        mvc.perform(get(phoneUrl).header("Authorization", KEY)
                        .param("filtering", "[{'field':'account_mode','operator':'EQUAL','value':'LIVE'}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        String qrUrl = "/dev/whatsapp/v26.0/100000000000002/message_qrdls";
        String result = mvc.perform(post(qrUrl).header("Authorization", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefilled_message\":\"Hello sandbox\",\"generate_qr_image\":\"SVG\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.qr_image_url").exists())
                .andReturn().getResponse().getContentAsString();
        String asset = java.net.URI.create(json.readTree(result).get("qr_image_url").asString()).getPath();
        mvc.perform(get(asset)).andExpect(status().isUnauthorized());
        mvc.perform(get(asset).header("Authorization", KEY)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg")));
    }

    @Test
    void importsDltWorkbookRowsAndRendersTheStoredTemplate() throws Exception {
        byte[] contents;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            writeRow(sheet.createRow(0), "SMS Header", "Template DLT ID", "Template Content", "Status");
            writeRow(sheet.createRow(1), "TESTER", "112233445566778899", "Hello {#var#}", "Approved");
            writeRow(sheet.createRow(2), "REJECT", "112233445566778900", "Ignored", "Rejected");
            workbook.write(output);
            contents = output.toByteArray();
        }
        var file = new MockMultipartFile("file", "templates.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, contents);
        mvc.perform(multipart("/dev/dlt_manager/add_template_excel").file(file)
                        .header("Authorization", KEY).param("entity_id", "123456"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imported").value(1));
        String list = mvc.perform(get("/dev/dlt_manager").param("type", "template").header("Authorization", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String template = json.readTree(list).get("data").get(0).get("message_id").asString();
        mvc.perform(get("/dev/bulkV2").header("Authorization", KEY).param("route", "dlt")
                        .param("sender_id", "TESTER").param("message", template)
                        .param("variables_values", "Sandbox").param("numbers", "9999999999"))
                .andExpect(status().isOk());
        worker.deliver();
        verify(mail).send(argThat((SimpleMailMessage message) -> message.getText().contains("Hello Sandbox")));
    }

    @Test
    void matchesSmsDetailsAndWhatsAppFieldErrorEnvelopes() throws Exception {
        mvc.perform(get("/dev/bulkV2").header("Authorization", KEY)
                        .param("route", "q").param("message", "Hello")
                        .param("numbers", "9999999999,invalid").param("sms_details", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sms_details.character_count").value(5))
                .andExpect(jsonPath("$.sms_details.valid_numbers[0]").value("9999999999"))
                .andExpect(jsonPath("$.sms_details.invalid_numbers[0]").value("invalid"))
                .andExpect(jsonPath("$.sms_details.amount_debited").value("0.0000"));
        mvc.perform(get("/dev/whatsapp").header("Authorization", KEY)
                        .param("message_id", "invalid").param("numbers", "9999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.return").value(false))
                .andExpect(jsonPath("$.errors_keys[0]").value("message_id"))
                .andExpect(jsonPath("$.errors.message_id[0]").exists());
    }



    @Test
    void distinguishesExpiredAndExhaustedOtpChallenges() throws Exception {
        String mobile = "9999999987";
        mvc.perform(post("/dev/otp/send").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp_id", "demo").param("otp", "123456"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE sandbox_otp_challenge SET expires_at = ? WHERE id LIKE ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), "%:" + mobile);
        mvc.perform(post("/dev/otp/verify").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp", "123456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("OTP expired"));

        jdbc.update("UPDATE sandbox_otp_challenge SET expires_at = ?, failures = 9 WHERE id LIKE ?",
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)), "%:" + mobile);
        mvc.perform(post("/dev/otp/verify").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp", "000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Maximum verification attempts reached. OTP is no longer valid."));
        mvc.perform(post("/dev/otp/verify").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp", "123456"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolvesDltManualIdsOnlyForAMatchingSavedTemplate() throws Exception {
        mvc.perform(post("/dev/dlt_manager/add_template").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"entity_id":"2001002001","template_id":"2001003001","sender_id":["TESTER"],
                                 "message_text":"Order {#var#} for {#var#} is ready."}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/dev/bulkV2").header("Authorization", KEY)
                        .param("route", "dlt_manual").param("sender_id", "TESTER").param("numbers", "9999999999")
                        .param("message", "Order 112 for Kim is ready."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return").value(true));
        mvc.perform(get("/dev/bulkV2").header("Authorization", KEY)
                        .param("route", "dlt_manual").param("sender_id", "TESTER").param("numbers", "9999999999")
                        .param("message", "Order 112 for Kim is ready. Pick it up today."))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status_code").value(425));
        worker.deliver();
        verify(mail).send(argThat((SimpleMailMessage message) -> message.getText().contains("Order 112 for Kim is ready.")
                && message.getText().contains("2001002001") && message.getText().contains("2001003001")));
    }

    @Test
    void acceptsUpToTheDocumentedRcsRichMessageLimit() throws Exception {
        mvc.perform(get("/dev/rcs").header("Authorization", KEY).param("bot_id", "1001").param("to", "9999999999")
                        .param("type", "rich").param("message", "x".repeat(3000)))
                .andExpect(status().isOk());
        mvc.perform(get("/dev/rcs").header("Authorization", KEY).param("bot_id", "1001").param("to", "9999999999")
                        .param("type", "rich").param("message", "x".repeat(3001)))
                .andExpect(status().isBadRequest());
    }


    @Test
    void appliesTheOtpCooldownWhenAlternatingTemplateIds() throws Exception {
        String mobile = "9999999986";
        for (String otpId : List.of("template-a", "template-b")) {
            mvc.perform(post("/dev/otp/send").header("Authorization", KEY)
                            .param("mobile", mobile).param("otp_id", otpId))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/dev/otp/send").header("Authorization", KEY)
                        .param("mobile", mobile).param("otp_id", "template-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("Please wait ")));
    }


    @Test
    void backfillsTheLastResendTimeForExistingOtpRows() {
        String url = "jdbc:h2:mem:otp_upgrade;MODE=MySQL;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
        var previous = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "sa", ""));
        String account = "a".repeat(64);
        String mobile = "9999999985";
        var issued = java.sql.Timestamp.valueOf("2026-01-05 10:00:00");
        var resent = java.sql.Timestamp.valueOf("2026-01-05 10:01:00");
        previous.update("""
                INSERT INTO sandbox_otp_challenge (id, code, payload, issued_at, expires_at, sends, failures, verified)
                VALUES (?, '123456', '{}', ?, ?, 2, 0, FALSE)
                """, account + ":" + mobile, issued, java.sql.Timestamp.valueOf("2026-01-05 10:15:00"));
        previous.update("""
                INSERT INTO sandbox_api_call (id, request_id, account, channel, operation, payload, recipients,
                    email_from, email_to, created_at, due_at, status, attempts)
                VALUES ('O123', 'O123', ?, 'OTP', 'OTP resend', '{}', ?, 'sender@example.test', 'inbox@example.test',
                    ?, ?, 'PENDING', 0)
                """, account, mobile, resent, resent);
        org.flywaydb.core.Flyway.configure().dataSource(url, "sa", "").load().migrate();
        assertThat(previous.queryForObject("SELECT last_sent_at FROM sandbox_otp_challenge WHERE id = ?",
                java.sql.Timestamp.class, account + ":" + mobile)).isEqualTo(resent);
    }


    @Test
    void swaggerDocumentsEveryGatewayRouteAndItsRequestMediaTypes() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("fast2smsKey")));
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));

        String result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.1.")))
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.components.securitySchemes.fast2smsKey.name").value("Authorization"))
                .andExpect(jsonPath("$.components.securitySchemes.fast2smsKey.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.fast2smsKey.type").value("apiKey"))
                .andExpect(jsonPath("$.security[0].fast2smsKey").isArray())
                .andExpect(jsonPath("$.paths['/health'].get.security").isEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = json.readTree(result);
        Set<String> expected = new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType().getPackageName().endsWith(".controller")) {
                mapping.getPatternValues().forEach(pattern -> mapping.getMethodsCondition().getMethods()
                        .forEach(method -> expected.add(method.name() + " " + pattern)));
            }
        });
        Set<String> actual = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        for (var resource : spec.path("paths").properties()) {
            for (var entry : resource.getValue().properties()) {
                if (!Set.of("get", "post", "put", "delete").contains(entry.getKey())) {
                    continue;
                }
                JsonNode operation = entry.getValue();
                assertThat(operationIds.add(operation.path("operationId").asString())).isTrue();
                assertThat(operation.path("responses").has("200")).as(resource.getKey()).isTrue();
                boolean secured = resource.getKey().startsWith("/dev/");
                assertThat(operation.path("responses").has("401")).as(resource.getKey()).isEqualTo(secured);
                if ("get".equals(entry.getKey())) {
                    assertThat(operation.has("requestBody")).as(resource.getKey()).isFalse();
                }
                for (String concretePath : documentedPaths(spec, resource.getKey(),
                        resource.getValue().path("parameters"))) {
                    verifyDocumentedMapping(concretePath, entry.getKey(), operation, actual);
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(spec.at("/paths/~1dev~1whatsapp~1{version}~1{phone_number_id}~1block_users/delete/requestBody")
                .isObject()).isTrue();
        JsonNode importMedia = spec.at("/paths/~1dev~1dlt_manager~1add_template_excel/post/requestBody/content");
        assertThat(importMedia.has("application/json")).isTrue();
        assertThat(importMedia.has("multipart/form-data")).isTrue();
    }

    @Test
    void swaggerQuickSmsExampleIsAnExecutableRequest() throws Exception {
        String result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode payload = json.readTree(result).at(
                "/paths/~1dev~1bulkV2/post/requestBody/content/application~1json/examples/quick/value");
        assertThat(payload.isObject()).isTrue();
        mvc.perform(post("/dev/bulkV2").header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return").value(true));
    }

    private List<String> documentedPaths(JsonNode spec, String path, JsonNode parameters) {
        List<String> paths = List.of(path);
        for (JsonNode parameter : parameters) {
            JsonNode declaration = parameter.has("$ref")
                    ? spec.at(parameter.path("$ref").asString().substring(1)) : parameter;
            assertThat(declaration.path("in").asString()).isEqualTo("path");
            assertThat(declaration.path("required").asBoolean()).isTrue();
            String name = "{" + declaration.path("name").asString() + "}";
            JsonNode schema = declaration.path("schema");
            var values = new ArrayList<String>();
            if (schema.has("enum")) {
                schema.path("enum").forEach(value -> values.add(value.asString()));
            } else {
                values.add(schema.path("example").asString());
            }
            paths = paths.stream().flatMap(value -> values.stream().map(item -> value.replace(name, item))).toList();
        }
        paths.forEach(value -> assertThat(value).doesNotContain("{", "}"));
        return paths;
    }

    private void verifyDocumentedMapping(String path, String method, JsonNode operation, Set<String> actual)
            throws Exception {
        String httpMethod = method.toUpperCase(java.util.Locale.ROOT);
        Set<String> mediaTypes = new HashSet<>();
        operation.path("requestBody").path("content").properties()
                .forEach(media -> mediaTypes.add(media.getKey()));
        if (mediaTypes.isEmpty()) {
            mediaTypes.add(MediaType.APPLICATION_JSON_VALUE);
        }
        for (String mediaType : mediaTypes) {
            var request = new MockHttpServletRequest(httpMethod, path);
            request.setContentType(mediaType);
            ServletRequestPathUtils.parseAndCache(request);
            assertThat(mappings.getHandler(request)).as(httpMethod + " " + path + " " + mediaType).isNotNull();
            actual.add(httpMethod + " " + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        }
    }


    private void writeRow(org.apache.poi.ss.usermodel.Row row, String... values) {
        for (int index = 0; index < values.length; index++) {
            row.createCell(index).setCellValue(values[index]);
        }
    }

}
