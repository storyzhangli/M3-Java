package com.zl.d32.document.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d32.common.GlobalExceptionHandler;
import com.zl.d32.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d32.document.retrieval.dto.SimilaritySearchResultView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * {@link SimilaritySearchController} 切片测试：验证统一 Result 协议与参数校验，
 * 不加载完整 Spring 上下文。
 */
class SimilaritySearchControllerTest {

    private SimilaritySearchService service;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(SimilaritySearchService.class);
        mockMvc = standaloneSetup(new SimilaritySearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validRequestReturnsSuccess() throws Exception {
        SimilaritySearchResultView resultView = new SimilaritySearchResultView();
        resultView.setQuery("空压机过热");
        resultView.setTopK(5);
        resultView.setMatches(List.of());
        when(service.search(any(SimilaritySearchRequest.class))).thenReturn(resultView);

        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("空压机过热", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.topK").value(5));
    }

    @Test
    void blankQueryReturnsBusinessError() throws Exception {
        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("   ", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void topKOutOfBoundReturnsBusinessError() throws Exception {
        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("水泵故障", 50))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void unreadableBodyReturnsBusinessError() throws Exception {
        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-a-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    private SimilaritySearchRequest payload(String query, Integer topK) {
        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        return request;
    }
}
