package com.example.fast2smsmockgateway.dtos.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendResponse(@JsonProperty("return") boolean accepted,
        @JsonProperty("request_id") String requestId, List<String> message,
        @JsonProperty("sms_details") Map<String, Object> smsDetails) {
}
