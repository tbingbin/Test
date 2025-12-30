package com.shanzhu.hospital.ai.vector;

import com.shanzhu.hospital.ai.client.DeepSeekClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 简单的内存向量存储（用于RAG）
 * 使用DeepSeek Embedding API生成向量
 * 实际生产环境建议使用专业的向量数据库如Milvus、Pinecone等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStore {
    
    private final DeepSeekClient deepSeekClient;
    
    private final List<VectorDocument> documents = new ArrayList<>();
    private final Map<String, double[]> embeddings = new HashMap<>();
    
    /**
     * 添加文档并自动生成向量嵌入
     */
    public void addDocument(String id, String content, String metadata) {
        VectorDocument doc = new VectorDocument();
        doc.setId(id);
        doc.setContent(content);
        doc.setMetadata(metadata);
        documents.add(doc);
        
        // 自动生成向量嵌入
        try {
            double[] embedding = deepSeekClient.createEmbedding(content);
            embeddings.put(id, embedding);
            log.info("成功为文档 {} 生成向量嵌入，维度: {}", id, embedding.length);
        } catch (Exception e) {
            log.error("为文档 {} 生成向量嵌入失败", id, e);
            // 如果生成失败，不阻止文档添加，但会在搜索时跳过
        }
    }
    
    /**
     * 手动设置文档的向量嵌入（用于批量导入等场景）
     */
    public void setEmbedding(String id, double[] embedding) {
        embeddings.put(id, embedding);
    }
    
    /**
     * 为查询文本生成向量并搜索
     * 
     * @param queryText 查询文本
     * @param topK 返回前K个结果
     * @return 相似文档列表
     */
    public List<VectorDocument> search(String queryText, int topK) {
        try {
            // 使用embedding API生成查询向量
            double[] queryEmbedding = deepSeekClient.createEmbedding(queryText);
            log.info("成功生成查询向量，维度: {}", queryEmbedding.length);
            return search(queryEmbedding, topK);
        } catch (Exception e) {
            log.error("生成查询向量失败", e);
            // 如果生成失败，返回空列表
            return new ArrayList<>();
        }
    }
    
    /**
     * 相似度搜索（使用已生成的向量）
     * 
     * @param queryEmbedding 查询向量
     * @param topK 返回前K个结果
     * @return 相似文档列表
     */
    public List<VectorDocument> search(double[] queryEmbedding, int topK) {
        List<DocumentScore> scores = new ArrayList<>();
        
        for (VectorDocument doc : documents) {
            double[] docEmbedding = embeddings.get(doc.getId());
            if (docEmbedding != null) {
                double similarity = cosineSimilarity(queryEmbedding, docEmbedding);
                scores.add(new DocumentScore(doc, similarity));
            }
        }
        
        // 按相似度排序，返回前K个
        return scores.stream()
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(topK)
            .map(DocumentScore::getDocument)
            .collect(Collectors.toList());
    }
    
    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    /**
     * 获取所有文档
     */
    public List<VectorDocument> getAllDocuments() {
        return new ArrayList<>(documents);
    }
    
    /**
     * 清空所有文档
     */
    public void clear() {
        documents.clear();
        embeddings.clear();
    }
    
    @Data
    public static class VectorDocument {
        private String id;
        private String content;
        private String metadata;
    }
    
    @Data
    private static class DocumentScore {
        private VectorDocument document;
        private double score;
        
        DocumentScore(VectorDocument document, double score) {
            this.document = document;
            this.score = score;
        }
    }
}

