package com.shanzhu.hospital.ai.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.shanzhu.hospital.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API客户端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {
    
    private final AiConfig aiConfig;
    
    /**
     * 流式对话
     * 
     * @param messages 对话消息列表
     * @param streamCallback 流式回调
     */
    public void streamChat(List<Map<String, String>> messages, StreamCallback streamCallback) {
        CloseableHttpClient httpClient = null;
        try {
            String url = aiConfig.getApiBaseUrl() + "/chat/completions";
            log.info("请求DeepSeek API: {}", url);
            log.info("请求消息数量: {}", messages.size());
            log.info("API Key前缀: {}", aiConfig.getApiKey() != null ? 
                aiConfig.getApiKey().substring(0, Math.min(10, aiConfig.getApiKey().length())) + "..." : "null");
            
            httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost(url);
            
            // 设置请求头
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Authorization", "Bearer " + aiConfig.getApiKey());
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", aiConfig.getModel());
            requestBody.put("messages", messages);
            requestBody.put("stream", true);
            requestBody.put("temperature", 0.7);
            
            String requestBodyJson = JSON.toJSONString(requestBody);
            log.info("流式请求体: {}", requestBodyJson);
            
            StringEntity entity = new StringEntity(requestBodyJson, StandardCharsets.UTF_8);
            httpPost.setEntity(entity);
            
            // 执行请求
            log.info("发送流式请求到DeepSeek API...");
            CloseableHttpResponse response = httpClient.execute(httpPost);
            
            // 检查HTTP状态码
            int statusCode = response.getStatusLine().getStatusCode();
            log.info("流式API响应状态码: {}", statusCode);
            
            if (statusCode != 200) {
                HttpEntity errorEntity = response.getEntity();
                String errorMessage = "API请求失败，状态码: " + statusCode;
                if (errorEntity != null) {
                    try {
                        String errorText = EntityUtils.toString(errorEntity, StandardCharsets.UTF_8);
                        log.error("API错误响应: {}", errorText);
                        JSONObject errorJson = JSON.parseObject(errorText);
                        if (errorJson.containsKey("error")) {
                            JSONObject error = errorJson.getJSONObject("error");
                            errorMessage = error.getString("message") != null ? 
                                error.getString("message") : errorMessage;
                        }
                    } catch (Exception e) {
                        log.warn("解析错误响应失败", e);
                    }
                }
                response.close();
                streamCallback.onError(new RuntimeException(errorMessage));
                return;
            }
            
            HttpEntity responseEntity = response.getEntity();
            
            if (responseEntity != null) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseEntity.getContent(), StandardCharsets.UTF_8)
                );
                
                String line;
                StringBuilder fullContent = new StringBuilder();
                boolean hasContent = false;
                
                while ((line = reader.readLine()) != null) {
                    log.debug("收到原始行: {}", line);
                    
                    // 跳过空行
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    // 处理SSE格式：data: {...} 或直接是JSON
                    String data = null;
                    if (line.startsWith("data: ")) {
                        data = line.substring(6).trim();
                    } else if (line.trim().startsWith("{")) {
                        data = line.trim();
                    }
                    
                    if (data != null && !data.isEmpty()) {
                        if ("[DONE]".equals(data)) {
                            log.info("收到结束标记 [DONE]");
                            break;
                        }
                        
                        try {
                            JSONObject jsonObject = JSON.parseObject(data);
                            log.debug("解析JSON对象: {}", jsonObject.toJSONString());
                            
                            // 检查是否有error字段
                            if (jsonObject.containsKey("error")) {
                                JSONObject error = jsonObject.getJSONObject("error");
                                String errorMsg = error != null ? error.getString("message") : "未知错误";
                                log.error("API返回错误: {}", errorMsg);
                                throw new RuntimeException("API错误: " + errorMsg);
                            }
                            
                            JSONArray choices = jsonObject.getJSONArray("choices");
                            if (choices != null && choices.size() > 0) {
                                JSONObject choice = choices.getJSONObject(0);
                                
                                // 检查是否有错误
                                String finishReason = choice.getString("finish_reason");
                                if (finishReason != null && "error".equals(finishReason)) {
                                    log.error("AI返回错误，finish_reason: error, 数据: {}", data);
                                    continue;
                                }
                                
                                JSONObject delta = choice.getJSONObject("delta");
                                if (delta != null) {
                                    String content = delta.getString("content");
                                    if (content != null && !content.isEmpty()) {
                                        hasContent = true;
                                        fullContent.append(content);
                                        log.debug("提取内容片段: {}", content);
                                        streamCallback.onContent(content);
                                    }
                                }
                            } else {
                                log.warn("响应中没有choices数组，数据: {}", data);
                            }
                        } catch (Exception e) {
                            log.warn("解析流式响应失败，行内容: {}, 错误: {}", line, e.getMessage());
                            // 如果是JSON解析错误，可能是格式问题，继续处理下一行
                            if (!(e instanceof com.alibaba.fastjson.JSONException)) {
                                throw e;
                            }
                        }
                    }
                }
                
                log.info("流式响应处理完成，收到内容长度: {}", fullContent.length());
                
                if (!hasContent && fullContent.length() == 0) {
                    log.error("未收到任何内容，可能是API响应格式异常");
                    String debugInfo = "未收到任何内容。请检查：\n" +
                        "1. API Key是否有效\n" +
                        "2. 网络连接是否正常\n" +
                        "3. DeepSeek API服务是否可用\n" +
                        "4. 查看后端日志获取详细错误信息";
                    streamCallback.onError(new RuntimeException(debugInfo));
                    return;
                }
                
                streamCallback.onComplete(fullContent.toString());
            } else {
                log.error("API响应实体为空");
                streamCallback.onError(new RuntimeException("API响应为空"));
                return;
            }
            
            response.close();
            if (httpClient != null) {
                httpClient.close();
            }
            
        } catch (Exception e) {
            log.error("流式对话请求失败: {}", e.getMessage(), e);
            // 打印详细错误信息
            if (e.getCause() != null) {
                log.error("错误原因: {}", e.getCause().getMessage());
            }
            // 打印堆栈跟踪
            e.printStackTrace();
            
            try {
                if (httpClient != null) {
                    httpClient.close();
                }
            } catch (Exception ex) {
                log.warn("关闭HTTP客户端失败", ex);
            }
            
            streamCallback.onError(e);
        }
    }
    
    /**
     * 普通对话（非流式）
     */
    public String chat(List<Map<String, String>> messages) {
        CloseableHttpClient httpClient = null;
        try {
            httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost(aiConfig.getApiBaseUrl() + "/chat/completions");
            
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Authorization", "Bearer " + aiConfig.getApiKey());
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", aiConfig.getModel());
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            
            StringEntity entity = new StringEntity(JSON.toJSONString(requestBody), StandardCharsets.UTF_8);
            httpPost.setEntity(entity);
            
            CloseableHttpResponse response = httpClient.execute(httpPost);
            
            // 检查HTTP状态码
            int statusCode = response.getStatusLine().getStatusCode();
            log.info("非流式API响应状态码: {}", statusCode);
            
            if (statusCode != 200) {
                HttpEntity errorEntity = response.getEntity();
                String errorMessage = "API请求失败，状态码: " + statusCode;
                if (errorEntity != null) {
                    try {
                        String errorText = EntityUtils.toString(errorEntity, StandardCharsets.UTF_8);
                        log.error("API错误响应: {}", errorText);
                        JSONObject errorJson = JSON.parseObject(errorText);
                        if (errorJson.containsKey("error")) {
                            JSONObject error = errorJson.getJSONObject("error");
                            errorMessage = error.getString("message") != null ? 
                                error.getString("message") : errorMessage;
                        }
                    } catch (Exception e) {
                        log.warn("解析错误响应失败", e);
                    }
                }
                response.close();
                httpClient.close();
                throw new RuntimeException(errorMessage);
            }
            
            HttpEntity responseEntity = response.getEntity();
            
            if (responseEntity != null) {
                String responseText = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
                log.info("API响应内容: {}", responseText);
                
                JSONObject jsonObject = JSON.parseObject(responseText);
                
                // 检查是否有error字段
                if (jsonObject.containsKey("error")) {
                    JSONObject error = jsonObject.getJSONObject("error");
                    String errorMsg = error != null ? error.getString("message") : "未知错误";
                    log.error("API返回错误: {}", errorMsg);
                    throw new RuntimeException("API错误: " + errorMsg);
                }
                
                JSONArray choices = jsonObject.getJSONArray("choices");
                if (choices != null && choices.size() > 0) {
                    JSONObject choice = choices.getJSONObject(0);
                    JSONObject message = choice.getJSONObject("message");
                    String content = message != null ? message.getString("content") : null;
                    if (content != null && !content.isEmpty()) {
                        return content;
                    } else {
                        log.warn("响应中content为空，完整响应: {}", responseText);
                        throw new RuntimeException("API返回空内容");
                    }
                } else {
                    log.warn("响应中没有choices数组，完整响应: {}", responseText);
                    throw new RuntimeException("API响应格式异常，缺少choices数组");
                }
            } else {
                log.error("API响应实体为空");
                throw new RuntimeException("API响应为空");
            }
            
        } catch (Exception e) {
            log.error("对话请求失败", e);
            e.printStackTrace();
            throw new RuntimeException("AI服务调用失败: " + e.getMessage(), e);
        } finally {
            try {
                if (httpClient != null) {
                    httpClient.close();
                }
            } catch (Exception e) {
                log.warn("关闭HTTP客户端失败", e);
            }
        }
    }
    
    /**
     * 生成文本嵌入向量（Embedding）
     * 
     * @param text 要生成向量的文本
     * @return 向量数组
     */
    public double[] createEmbedding(String text) {
        CloseableHttpClient httpClient = null;
        try {
            String url = aiConfig.getApiBaseUrl() + "/embeddings";
            log.info("请求DeepSeek Embedding API: {}", url);
            
            httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost(url);
            
            // 设置请求头
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Authorization", "Bearer " + aiConfig.getApiKey());
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "deepseek-embedding"); // DeepSeek的embedding模型
            requestBody.put("input", text);
            
            String requestBodyJson = JSON.toJSONString(requestBody);
            log.debug("Embedding请求体: {}", requestBodyJson);
            
            StringEntity entity = new StringEntity(requestBodyJson, StandardCharsets.UTF_8);
            httpPost.setEntity(entity);
            
            // 执行请求
            CloseableHttpResponse response = httpClient.execute(httpPost);
            
            // 检查HTTP状态码
            int statusCode = response.getStatusLine().getStatusCode();
            log.info("Embedding API响应状态码: {}", statusCode);
            
            if (statusCode != 200) {
                HttpEntity errorEntity = response.getEntity();
                String errorMessage = "Embedding API请求失败，状态码: " + statusCode;
                if (errorEntity != null) {
                    try {
                        String errorText = EntityUtils.toString(errorEntity, StandardCharsets.UTF_8);
                        log.error("Embedding API错误响应: {}", errorText);
                        JSONObject errorJson = JSON.parseObject(errorText);
                        if (errorJson.containsKey("error")) {
                            JSONObject error = errorJson.getJSONObject("error");
                            errorMessage = error.getString("message") != null ? 
                                error.getString("message") : errorMessage;
                        }
                    } catch (Exception e) {
                        log.warn("解析错误响应失败", e);
                    }
                }
                response.close();
                httpClient.close();
                throw new RuntimeException(errorMessage);
            }
            
            HttpEntity responseEntity = response.getEntity();
            
            if (responseEntity != null) {
                String responseText = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
                log.debug("Embedding API响应内容: {}", responseText);
                
                JSONObject jsonObject = JSON.parseObject(responseText);
                
                // 检查是否有error字段
                if (jsonObject.containsKey("error")) {
                    JSONObject error = jsonObject.getJSONObject("error");
                    String errorMsg = error != null ? error.getString("message") : "未知错误";
                    log.error("Embedding API返回错误: {}", errorMsg);
                    throw new RuntimeException("Embedding API错误: " + errorMsg);
                }
                
                JSONArray data = jsonObject.getJSONArray("data");
                if (data != null && data.size() > 0) {
                    JSONObject firstItem = data.getJSONObject(0);
                    JSONArray embedding = firstItem.getJSONArray("embedding");
                    if (embedding != null) {
                        double[] vector = new double[embedding.size()];
                        for (int i = 0; i < embedding.size(); i++) {
                            vector[i] = embedding.getDoubleValue(i);
                        }
                        log.info("成功生成向量，维度: {}", vector.length);
                        return vector;
                    } else {
                        log.warn("响应中embedding为空，完整响应: {}", responseText);
                        throw new RuntimeException("Embedding API返回空向量");
                    }
                } else {
                    log.warn("响应中没有data数组，完整响应: {}", responseText);
                    throw new RuntimeException("Embedding API响应格式异常，缺少data数组");
                }
            } else {
                log.error("Embedding API响应实体为空");
                throw new RuntimeException("Embedding API响应为空");
            }
            
        } catch (Exception e) {
            log.error("Embedding请求失败", e);
            e.printStackTrace();
            throw new RuntimeException("Embedding API调用失败: " + e.getMessage(), e);
        } finally {
            try {
                if (httpClient != null) {
                    httpClient.close();
                }
            } catch (Exception e) {
                log.warn("关闭HTTP客户端失败", e);
            }
        }
    }
    
    /**
     * 使用NLP模型进行意图识别
     * 
     * @param userMessage 用户消息
     * @param conversationHistory 对话历史
     * @return 识别的意图
     */
    public String recognizeIntentWithNLP(String userMessage, List<Map<String, String>> conversationHistory) {
        try {
            // 构建意图识别的系统提示词
            List<Map<String, String>> messages = new ArrayList<>();
            
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是一个意图识别助手。根据用户消息，识别用户的意图。\n" +
                "可能的意图包括：\n" +
                "1. QUERY_SCHEDULE - 查询排班、预约、挂号、时间相关\n" +
                "2. RECOMMEND_DOCTOR - 推荐医生、哪个医生好、评价好的医生\n" +
                "3. FIND_DEPARTMENT - 科室选择、症状咨询、挂什么科\n" +
                "4. QUERY_POLICY - 流程、须知、注意事项\n" +
                "5. GENERAL_QA - 其他一般性问题\n\n" +
                "请只返回意图代码，不要返回其他内容。");
            messages.add(systemMsg);
            
            // 添加上下文（最近3轮对话）
            if (conversationHistory != null && !conversationHistory.isEmpty()) {
                int startIndex = Math.max(0, conversationHistory.size() - 6); // 最近3轮（每轮2条消息）
                for (int i = startIndex; i < conversationHistory.size(); i++) {
                    messages.add(conversationHistory.get(i));
                }
            }
            
            // 添加当前用户消息
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", "识别以下消息的意图：" + userMessage);
            messages.add(userMsg);
            
            // 调用API进行意图识别
            String response = chat(messages);
            
            // 解析响应，提取意图代码
            String intent = response.trim().toUpperCase();
            
            // 验证意图是否有效
            String[] validIntents = {"QUERY_SCHEDULE", "RECOMMEND_DOCTOR", "FIND_DEPARTMENT", "QUERY_POLICY", "GENERAL_QA"};
            for (String validIntent : validIntents) {
                if (intent.contains(validIntent)) {
                    log.info("NLP识别意图: {}", validIntent);
                    return validIntent;
                }
            }
            
            // 如果无法识别，使用关键词匹配作为后备方案
            log.warn("NLP无法识别意图，使用关键词匹配作为后备方案");
            return recognizeIntentFallback(userMessage);
            
        } catch (Exception e) {
            log.error("NLP意图识别失败，使用关键词匹配作为后备方案", e);
            return recognizeIntentFallback(userMessage);
        }
    }
    
    /**
     * 后备意图识别方法（关键词匹配）
     */
    private String recognizeIntentFallback(String userMessage) {
        String message = userMessage.toLowerCase();
        
        // 查询排班意图
        if (message.contains("排班") || message.contains("预约") || message.contains("挂号") || 
            message.contains("什么时候") || message.contains("时间") || 
            message.contains("今天") || message.contains("明天") || message.contains("后天") ||
            message.matches(".*\\d{1,2}月\\d{1,2}号.*") || message.matches(".*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*") ||
            message.contains("能看") || message.contains("有医生")) {
            return "QUERY_SCHEDULE";
        }
        
        // 推荐医生意图
        if (message.contains("推荐") || message.contains("哪个医生") || message.contains("哪个大夫") ||
            message.contains("好医生") || message.contains("评价好") || message.contains("评分高") ||
            message.contains("口碑好") || message.contains("推荐医生")) {
            return "RECOMMEND_DOCTOR";
        }
        
        // 流程查询意图
        if (message.contains("流程") || message.contains("怎么") || message.contains("如何") ||
            message.contains("须知") || message.contains("注意")) {
            return "QUERY_POLICY";
        }
        
        // 科室选择意图
        if (message.contains("科室") || message.contains("挂什么") || message.contains("看什么") ||
            message.contains("症状") || message.contains("难受") || message.contains("疼")) {
            return "FIND_DEPARTMENT";
        }
        
        return "GENERAL_QA";
    }
    
    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onContent(String content);
        void onComplete(String fullContent);
        void onError(Exception e);
    }
}

