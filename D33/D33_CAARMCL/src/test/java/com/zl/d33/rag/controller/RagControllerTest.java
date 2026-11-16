package com.zl.d33.rag.controller;

import com.zl.d33.rag.dto.RagAnswerResponse;
import com.zl.d33.rag.dto.RagQuestionRequest;
import com.zl.d33.rag.model.GenerationStatus;
import com.zl.d33.rag.model.RagErrorCode;
import com.zl.d33.rag.model.RetrievalStatus;
import com.zl.d33.rag.orchestrator.RagOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 控制器 MockMvc 测试：参数校验与统一响应。
 */
@ExtendWith(MockitoExtension.class)
class RagControllerTest {

    @Mock
    private RagOrchestrator ragOrchestrator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new RagController(ragOrchestrator))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("合法请求返回统一包装的回答")
    void shouldReturnWrappedAnswer() throws Exception {
        RagAnswerResponse response = new RagAnswerResponse();
        response.setAnswer("受控回答");
        response.setRetrievalStatus(RetrievalStatus.SUCCESS);
        response.setGenerationStatus(GenerationStatus.SUCCESS);
        response.setErrorCode(RagErrorCode.NONE);
        when(ragOrchestrator.ask(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"如何处理气蚀？\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("受控回答"))
                .andExpect(jsonPath("$.data.generationStatus").value("SUCCESS"));
    }

    @Test
    @DisplayName("空白问题触发校验错误")
    void shouldRejectBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("topK 越界触发校验错误")
    void shouldRejectInvalidTopK() throws Exception {
        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"问题\",\"topK\":99}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("受控失败响应如实返回错误码")
    void shouldReturnControlledFailure() throws Exception {
        RagAnswerResponse response = new RagAnswerResponse();
        response.setRetrievalStatus(RetrievalStatus.FAILED);
        response.setGenerationStatus(GenerationStatus.NOT_ATTEMPTED);
        response.setErrorCode(RagErrorCode.RETRIEVAL_FAILED);
        when(ragOrchestrator.ask(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorCode").value("RETRIEVAL_FAILED"));
    }
}
