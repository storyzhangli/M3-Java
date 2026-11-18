package com.zl.d35.rag.controller;

import com.zl.d35.rag.dto.RagAnswerResponse;
import com.zl.d35.rag.dto.RagQuestionRequest;
import com.zl.d35.rag.dto.RagSourceView;
import com.zl.d35.rag.model.GenerationStatus;
import com.zl.d35.rag.model.RagErrorCode;
import com.zl.d35.rag.orchestrator.RagOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 接口 MockMvc 集成测试：验证序列化契约（NON_NULL 隐藏、无 url 字段、溯源字段齐全）。
 */
@ExtendWith(MockitoExtension.class)
class RagControllerMockMvcTest {

    private MockMvc mockMvc;

    @Mock
    private RagOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RagController(orchestrator)).build();
    }

    private RagSourceView source(String citationId, Integer page) {
        RagSourceView view = new RagSourceView();
        view.setSourceId("doc-1");
        view.setCitationId(citationId);
        view.setSourceRef(citationId);
        view.setDocumentId("doc-1");
        view.setChunkId("chunk-1");
        view.setTitle("设备手册");
        view.setPage(page);
        view.setQuotedSnippet("真实引用片段");
        view.setCited(true);
        view.setUrl(null);
        return view;
    }

    @Test
    @DisplayName("响应包含溯源字段，null 字段（url/缺失 page）被隐藏")
    void serializationContract() throws Exception {
        RagAnswerResponse answer = new RagAnswerResponse();
        answer.setAnswer("答案 [S1]");
        answer.setGenerationStatus(GenerationStatus.SUCCESS);
        answer.setErrorCode(RagErrorCode.NONE);
        answer.setSources(List.of(source("S1", 3), source("S2", null)));
        when(orchestrator.ask(any(RagQuestionRequest.class))).thenReturn(answer);

        mockMvc.perform(post("/api/v1/rag/ask")
                        .contentType("application/json")
                        .content("""
                                {"question":"工作压力是多少","topK":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sources[0].sourceId").value("doc-1"))
                .andExpect(jsonPath("$.data.sources[0].citationId").value("S1"))
                .andExpect(jsonPath("$.data.sources[0].page").value(3))
                .andExpect(jsonPath("$.data.sources[0].quotedSnippet").value("真实引用片段"))
                .andExpect(jsonPath("$.data.sources[0].cited").value(true))
                // url 恒 null 被 NON_NULL 隐藏
                .andExpect(jsonPath("$.data.sources[0].url").doesNotExist())
                // 第二条 page 缺失，不输出假页码
                .andExpect(jsonPath("$.data.sources[1].page").doesNotExist())
                .andExpect(jsonPath("$.data.sources[1].url").doesNotExist());
    }
}
