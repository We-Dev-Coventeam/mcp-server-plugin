/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Jenkins community.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for MCP schema compatibility with newer client versions.
 * These tests ensure the plugin can parse MCP messages from clients
 * that use newer versions of the MCP specification.
 */
public class McpSchemaCompatibilityTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Create ObjectMapper with the same configuration as Endpoint
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    @DisplayName("Should parse ClientCapabilities with elicitation.form field from newer MCP clients")
    void shouldParseClientCapabilitiesWithElicitationForm() throws Exception {
        // JSON payload from a newer MCP client (e.g., VS Code Copilot MCP)
        // that includes elicitation.form which is not in SDK 0.17.0
        String jsonWithElicitationForm = """
                {
                    "experimental": null,
                    "roots": {
                        "listChanged": true
                    },
                    "sampling": {},
                    "elicitation": {
                        "form": true
                    }
                }
                """;

        assertThatCode(() -> {
            McpSchema.ClientCapabilities capabilities =
                    objectMapper.readValue(jsonWithElicitationForm, McpSchema.ClientCapabilities.class);
            assertThat(capabilities).isNotNull();
            assertThat(capabilities.elicitation()).isNotNull();
            assertThat(capabilities.roots()).isNotNull();
            assertThat(capabilities.roots().listChanged()).isTrue();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should parse InitializeRequest with elicitation.form from newer MCP clients")
    void shouldParseInitializeRequestWithElicitationForm() throws Exception {
        // Full initialize request payload with elicitation.form
        String initializeRequestJson = """
                {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {
                        "roots": {
                            "listChanged": true
                        },
                        "sampling": {},
                        "elicitation": {
                            "form": true
                        }
                    },
                    "clientInfo": {
                        "name": "vscode-copilot-mcp",
                        "version": "1.0.0"
                    }
                }
                """;

        assertThatCode(() -> {
            McpSchema.InitializeRequest request =
                    objectMapper.readValue(initializeRequestJson, McpSchema.InitializeRequest.class);
            assertThat(request).isNotNull();
            assertThat(request.protocolVersion()).isEqualTo("2024-11-05");
            assertThat(request.capabilities()).isNotNull();
            assertThat(request.capabilities().elicitation()).isNotNull();
            assertThat(request.clientInfo()).isNotNull();
            assertThat(request.clientInfo().name()).isEqualTo("vscode-copilot-mcp");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should parse ClientCapabilities with unknown experimental fields")
    void shouldParseClientCapabilitiesWithUnknownExperimentalFields() throws Exception {
        String jsonWithExperimental = """
                {
                    "experimental": {
                        "futureFeature": true,
                        "anotherUnknownField": {
                            "nested": "value"
                        }
                    },
                    "roots": null,
                    "sampling": null,
                    "elicitation": null
                }
                """;

        assertThatCode(() -> {
            McpSchema.ClientCapabilities capabilities =
                    objectMapper.readValue(jsonWithExperimental, McpSchema.ClientCapabilities.class);
            assertThat(capabilities).isNotNull();
            assertThat(capabilities.experimental()).isNotNull();
            assertThat(capabilities.experimental()).containsKey("futureFeature");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should parse ClientCapabilities with additional unknown top-level fields")
    void shouldParseClientCapabilitiesWithUnknownTopLevelFields() throws Exception {
        // Simulate a future MCP spec version with new top-level capability
        String jsonWithUnknownTopLevel = """
                {
                    "roots": {
                        "listChanged": false
                    },
                    "sampling": {},
                    "elicitation": {},
                    "futureCapability": {
                        "enabled": true
                    }
                }
                """;

        assertThatCode(() -> {
            McpSchema.ClientCapabilities capabilities =
                    objectMapper.readValue(jsonWithUnknownTopLevel, McpSchema.ClientCapabilities.class);
            assertThat(capabilities).isNotNull();
            // Unknown field should be ignored, not cause an error
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should fail to parse with default ObjectMapper (without FAIL_ON_UNKNOWN_PROPERTIES=false)")
    void shouldFailWithDefaultObjectMapper() {
        // This test verifies that WITHOUT the fix, parsing would fail
        ObjectMapper strictMapper = new ObjectMapper();
        // Do NOT configure FAIL_ON_UNKNOWN_PROPERTIES = false

        String jsonWithElicitationForm = """
                {
                    "elicitation": {
                        "form": true
                    }
                }
                """;

        // The Elicitation record doesn't have @JsonIgnoreProperties, so this should fail
        org.junit.jupiter.api.Assertions.assertThrows(
                com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class, () -> {
                    strictMapper.readValue(jsonWithElicitationForm, McpSchema.ClientCapabilities.class);
                });
    }

    @Test
    @DisplayName("Should parse Sampling capability with unknown fields")
    void shouldParseSamplingWithUnknownFields() throws Exception {
        String jsonWithSamplingExtras = """
                {
                    "sampling": {
                        "unknownSamplingField": "value"
                    }
                }
                """;

        assertThatCode(() -> {
            McpSchema.ClientCapabilities capabilities =
                    objectMapper.readValue(jsonWithSamplingExtras, McpSchema.ClientCapabilities.class);
            assertThat(capabilities).isNotNull();
            assertThat(capabilities.sampling()).isNotNull();
        }).doesNotThrowAnyException();
    }
}
