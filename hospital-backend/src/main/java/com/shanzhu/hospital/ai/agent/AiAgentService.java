package com.shanzhu.hospital.ai.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.shanzhu.hospital.ai.client.DeepSeekClient;
import com.shanzhu.hospital.ai.vector.VectorStore;
import com.shanzhu.hospital.entity.po.Arrange;
import com.shanzhu.hospital.entity.po.Doctor;
import com.shanzhu.hospital.entity.po.Review;
import com.shanzhu.hospital.entity.vo.DoctorListVo;
import com.shanzhu.hospital.entity.vo.DoctorPageVo;
import com.shanzhu.hospital.entity.vo.ReviewPageVo;
import com.shanzhu.hospital.mapper.ArrangeMapper;
import com.shanzhu.hospital.mapper.DoctorUserMapper;
import com.shanzhu.hospital.service.ArrangeService;
import com.shanzhu.hospital.service.DoctorUserService;
import com.shanzhu.hospital.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI Agent服务
 * 支持意图识别、Function Calling、多轮对话
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentService {
    
    private final DeepSeekClient deepSeekClient;
    private final VectorStore vectorStore;
    private final ArrangeMapper arrangeMapper;
    private final DoctorUserMapper doctorUserMapper;
    private final ArrangeService arrangeService;
    private final ReviewService reviewService;
    private final DoctorUserService doctorUserService;
    
    /**
     * 处理用户消息
     * 
     * @param userMessage 用户消息
     * @param conversationHistory 对话历史
     * @param streamCallback 流式回调
     */
    public void processMessage(String userMessage, List<Map<String, String>> conversationHistory, 
                              DeepSeekClient.StreamCallback streamCallback) {
        try {
            // 1. 意图识别
            String intent = recognizeIntent(userMessage, conversationHistory);
            log.info("识别意图: {}", intent);
            
            // 2. 根据意图处理
            List<Map<String, String>> messages = buildMessages(userMessage, conversationHistory, intent);
            
            // 3. 流式调用AI
            deepSeekClient.streamChat(messages, streamCallback);
            
        } catch (Exception e) {
            log.error("处理消息失败", e);
            streamCallback.onError(e);
        }
    }
    
    /**
     * 意图识别（使用NLP模型）
     */
    private String recognizeIntent(String userMessage, List<Map<String, String>> conversationHistory) {
        try {
            // 使用NLP模型进行意图识别
            return deepSeekClient.recognizeIntentWithNLP(userMessage, conversationHistory);
        } catch (Exception e) {
            log.error("NLP意图识别失败，使用关键词匹配作为后备方案", e);
            // 如果NLP识别失败，使用关键词匹配作为后备方案
            return recognizeIntentFallback(userMessage);
        }
    }
    
    /**
     * 后备意图识别方法（关键词匹配）
     */
    private String recognizeIntentFallback(String userMessage) {
        String message = userMessage.toLowerCase();
        
        // 查询排班意图（包括日期查询）
        if (message.contains("排班") || message.contains("预约") || message.contains("挂号") || 
            message.contains("什么时候") || message.contains("时间") || 
            message.contains("今天") || message.contains("明天") || message.contains("后天") ||
            message.matches(".*\\d{1,2}月\\d{1,2}号.*") || message.matches(".*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*") ||
            message.contains("能看") || message.contains("有医生")) {
            return "QUERY_SCHEDULE";
        }
        
        // 推荐医生意图（根据评价）
        if (message.contains("推荐") || message.contains("哪个医生") || message.contains("哪个大夫") ||
            message.contains("好医生") || message.contains("评价好") || message.contains("评分高") ||
            message.contains("口碑好") || message.contains("推荐医生")) {
            return "RECOMMEND_DOCTOR";
        }
        
        if (message.contains("流程") || message.contains("怎么") || message.contains("如何") ||
            message.contains("须知") || message.contains("注意")) {
            return "QUERY_POLICY";
        }
        
        if (message.contains("科室") || message.contains("挂什么") || message.contains("看什么") ||
            message.contains("症状") || message.contains("难受") || message.contains("疼")) {
            return "FIND_DEPARTMENT";
        }
        
        return "GENERAL_QA";
    }
    
    /**
     * 构建消息列表
     */
    private List<Map<String, String>> buildMessages(String userMessage, 
                                                   List<Map<String, String>> conversationHistory,
                                                   String intent) {
        List<Map<String, String>> messages = new ArrayList<>();
        
        // System Prompt
        String systemPrompt = buildSystemPrompt(intent);
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);
        
        // 添加对话历史
        messages.addAll(conversationHistory);
        
        // 根据意图获取数据并添加到上下文
        String contextData = "";
        if ("QUERY_SCHEDULE".equals(intent)) {
            contextData = queryScheduleData(userMessage);
        } else if ("RECOMMEND_DOCTOR".equals(intent)) {
            contextData = recommendDoctorData(userMessage);
        }
        
        // 添加当前用户消息（如果有关联数据，添加到消息中）
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        if (!contextData.isEmpty()) {
            userMsg.put("content", userMessage + "\n\n【系统查询结果】\n" + contextData);
        } else {
            userMsg.put("content", userMessage);
        }
        messages.add(userMsg);
        
        return messages;
    }
    
    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String intent) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("你是一个【医院智能分诊助手】。\n\n");
        
        // 根据意图添加不同的提示
        switch (intent) {
            case "FIND_DEPARTMENT":
                prompt.append("【你的职责】\n");
                prompt.append("- 根据患者提供的症状描述，在给定的候选科室中，选择最合适的一个\n");
                prompt.append("- 你的回答仅用于\"预约挂号辅助\"，不构成任何医疗诊断或治疗建议\n\n");
                prompt.append("【严格限制】\n");
                prompt.append("- 你不能新增候选科室\n");
                prompt.append("- 你不能给出治疗方案\n");
                prompt.append("- 如果患者描述不清晰，你应该追问具体症状，而不是胡乱猜测\n\n");
                
                // 添加科室信息
                prompt.append("【候选科室列表】\n");
                List<VectorStore.VectorDocument> deptDocs = vectorStore.getAllDocuments().stream()
                    .filter(doc -> doc.getMetadata() != null && doc.getMetadata().contains("科室介绍"))
                    .collect(Collectors.toList());
                for (VectorStore.VectorDocument doc : deptDocs) {
                    prompt.append(doc.getContent()).append("\n");
                }
                break;
                
            case "QUERY_SCHEDULE":
                prompt.append("【你的职责】\n");
                prompt.append("- 帮助患者查询医生排班信息\n");
                prompt.append("- 系统已经为你查询了排班数据，请根据【系统查询结果】中的信息回答患者\n");
                prompt.append("- 如果查询结果为空，请告知患者该日期或科室暂无排班\n");
                prompt.append("- 回答要清晰、准确，包含医生姓名、科室、日期、时间段等信息\n\n");
                break;
                
            case "RECOMMEND_DOCTOR":
                prompt.append("【你的职责】\n");
                prompt.append("- 根据患者需求推荐合适的医生\n");
                prompt.append("- 系统已经为你查询了医生评价数据，请根据【系统查询结果】中的信息推荐医生\n");
                prompt.append("- 优先推荐评分高、评价好的医生\n");
                prompt.append("- 如果患者提到科室，优先推荐该科室的医生\n");
                prompt.append("- 回答要包含医生姓名、科室、职位、评分、评价等信息\n\n");
                break;
                
            case "QUERY_POLICY":
                prompt.append("【你的职责】\n");
                prompt.append("- 回答患者关于挂号流程、就诊须知、检查项目说明等问题\n");
                prompt.append("- 使用通俗、简洁的语言\n");
                prompt.append("- 若问题超出范围，请明确说明无法回答\n\n");
                
                // 添加就诊须知信息
                List<VectorStore.VectorDocument> policyDocs = vectorStore.getAllDocuments().stream()
                    .filter(doc -> doc.getMetadata() != null && doc.getMetadata().contains("就诊须知"))
                    .collect(Collectors.toList());
                for (VectorStore.VectorDocument doc : policyDocs) {
                    prompt.append(doc.getContent()).append("\n");
                }
                break;
                
            default:
                prompt.append("【你的职责】\n");
                prompt.append("- 回答患者关于医院常规就诊事项的问题\n");
                prompt.append("- 使用通俗、简洁的语言\n");
                prompt.append("- 禁止回答疾病诊断、用药建议、个性化治疗方案\n\n");
        }
        
        return prompt.toString();
    }
    
    /**
     * 查询排班数据
     */
    private String queryScheduleData(String userMessage) {
        try {
            // 解析日期
            String date = parseDate(userMessage);
            if (date == null) {
                date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            
            // 解析科室
            String section = parseSection(userMessage);
            
            log.info("查询排班 - 日期: {}, 科室: {}", date, section);
            
            // 查询排班
            List<Arrange> arranges = arrangeService.findArrange(date, section);
            
            if (arranges == null || arranges.isEmpty()) {
                return String.format("查询结果：%s %s暂无排班信息", date, section != null ? section + " " : "");
            }
            
            // 构建结果
            StringBuilder result = new StringBuilder();
            result.append(String.format("查询日期：%s\n", date));
            if (section != null) {
                result.append(String.format("查询科室：%s\n", section));
            }
            result.append("\n排班信息：\n");
            
            for (Arrange arrange : arranges) {
                if (arrange.getDoctor() != null) {
                    Doctor doctor = arrange.getDoctor();
                    result.append(String.format("- 医生：%s（%s，%s）\n", 
                        doctor.getDName(), 
                        doctor.getDSection() != null ? doctor.getDSection() : "未知科室",
                        doctor.getDPost() != null ? doctor.getDPost() : "医生"));
                    if (doctor.getDAvgStar() != null && doctor.getDAvgStar() > 0) {
                        result.append(String.format("  评分：%.1f分（%d人评价）\n", 
                            doctor.getDAvgStar(), 
                            doctor.getDPeople() != null ? doctor.getDPeople() : 0));
                    }
                } else {
                    result.append(String.format("- 排班ID：%s，医生ID：%d\n", 
                        arrange.getArId(), arrange.getDId()));
                }
            }
            
            return result.toString();
            
        } catch (Exception e) {
            log.error("查询排班数据失败", e);
            return "查询排班信息失败：" + e.getMessage();
        }
    }
    
    /**
     * 推荐医生数据
     */
    private String recommendDoctorData(String userMessage) {
        try {
            // 解析科室
            String section = parseSection(userMessage);
            
            log.info("推荐医生 - 科室: {}", section);
            
            // 查询所有医生
            List<Doctor> allDoctors = new ArrayList<>();
            if (section != null && !section.isEmpty()) {
                // 查询指定科室的医生
                DoctorListVo doctorListVo = doctorUserService.findDoctorBySection(section);
                if (doctorListVo != null && doctorListVo.getDoctors() != null) {
                    allDoctors.addAll(doctorListVo.getDoctors());
                }
            } else {
                // 查询所有医生（分页查询，这里简化处理）
                DoctorPageVo doctorPageVo = doctorUserService.findDoctorPage(1, 100, "");
                if (doctorPageVo != null && doctorPageVo.getDoctors() != null) {
                    allDoctors.addAll(doctorPageVo.getDoctors());
                }
            }
            
            if (allDoctors.isEmpty()) {
                return "未找到符合条件的医生";
            }
            
            // 获取每个医生的评价信息
            List<Map<String, Object>> doctorWithReviews = new ArrayList<>();
            for (Doctor doctor : allDoctors) {
                // 查询该医生的评价
                ReviewPageVo reviewPageVo = reviewService.getReviewList(1, 10, doctor.getDId());
                
                Map<String, Object> doctorInfo = new HashMap<>();
                doctorInfo.put("dId", doctor.getDId());
                doctorInfo.put("dName", doctor.getDName());
                doctorInfo.put("dSection", doctor.getDSection());
                doctorInfo.put("dPost", doctor.getDPost());
                doctorInfo.put("dIntroduction", doctor.getDIntroduction());
                doctorInfo.put("dPrice", doctor.getDPrice());
                doctorInfo.put("dAvgStar", doctor.getDAvgStar() != null ? doctor.getDAvgStar() : 0.0);
                doctorInfo.put("dPeople", doctor.getDPeople() != null ? doctor.getDPeople() : 0);
                
                // 添加评价详情
                if (reviewPageVo != null && reviewPageVo.getReviews() != null && !reviewPageVo.getReviews().isEmpty()) {
                    List<Map<String, Object>> reviews = new ArrayList<>();
                    for (Review review : reviewPageVo.getReviews()) {
                        Map<String, Object> reviewInfo = new HashMap<>();
                        reviewInfo.put("rStar", review.getRStar());
                        reviewInfo.put("rContent", review.getRContent());
                        reviewInfo.put("rImpressions", review.getRImpressions());
                        reviews.add(reviewInfo);
                    }
                    doctorInfo.put("reviews", reviews);
                }
                
                doctorWithReviews.add(doctorInfo);
            }
            
            // 按评分排序
            doctorWithReviews.sort((a, b) -> {
                Double starA = (Double) a.get("dAvgStar");
                Double starB = (Double) b.get("dAvgStar");
                if (starA == null) starA = 0.0;
                if (starB == null) starB = 0.0;
                return starB.compareTo(starA);
            });
            
            // 构建结果
            StringBuilder result = new StringBuilder();
            if (section != null) {
                result.append(String.format("科室：%s\n", section));
            }
            result.append("\n推荐医生（按评分排序）：\n\n");
            
            int count = 0;
            for (Map<String, Object> doctorInfo : doctorWithReviews) {
                if (count >= 5) break; // 只推荐前5名
                count++;
                
                result.append(String.format("%d. %s（%s，%s）\n", 
                    count,
                    doctorInfo.get("dName"),
                    doctorInfo.get("dSection"),
                    doctorInfo.get("dPost")));
                
                Double avgStar = (Double) doctorInfo.get("dAvgStar");
                Integer people = (Integer) doctorInfo.get("dPeople");
                if (avgStar != null && avgStar > 0) {
                    result.append(String.format("   评分：%.1f分（%d人评价）\n", avgStar, people != null ? people : 0));
                }
                
                String intro = (String) doctorInfo.get("dIntroduction");
                if (intro != null && !intro.isEmpty()) {
                    result.append(String.format("   简介：%s\n", intro));
                }
                
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> reviews = (List<Map<String, Object>>) doctorInfo.get("reviews");
                if (reviews != null && !reviews.isEmpty()) {
                    result.append("   最近评价：\n");
                    for (int i = 0; i < Math.min(2, reviews.size()); i++) {
                        Map<String, Object> review = reviews.get(i);
                        String content = (String) review.get("rContent");
                        if (content != null && !content.isEmpty()) {
                            result.append(String.format("     - %s\n", content.length() > 50 ? content.substring(0, 50) + "..." : content));
                        }
                    }
                }
                
                result.append("\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            log.error("推荐医生数据失败", e);
            return "推荐医生失败：" + e.getMessage();
        }
    }
    
    /**
     * 解析日期
     */
    private String parseDate(String message) {
        try {
            // 今天
            if (message.contains("今天") || message.contains("今日")) {
                return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            
            // 明天
            if (message.contains("明天") || message.contains("明日")) {
                return LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            
            // 后天
            if (message.contains("后天")) {
                return LocalDate.now().plusDays(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
            
            // 解析 "12月19号" 格式
            Pattern pattern1 = Pattern.compile("(\\d{1,2})月(\\d{1,2})号");
            Matcher matcher1 = pattern1.matcher(message);
            if (matcher1.find()) {
                int month = Integer.parseInt(matcher1.group(1));
                int day = Integer.parseInt(matcher1.group(2));
                int year = LocalDate.now().getYear();
                try {
                    LocalDate date = LocalDate.of(year, month, day);
                    // 如果日期已过，则认为是明年
                    if (date.isBefore(LocalDate.now())) {
                        date = LocalDate.of(year + 1, month, day);
                    }
                    return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                } catch (DateTimeParseException e) {
                    log.warn("日期解析失败: {}-{}-{}", year, month, day);
                }
            }
            
            // 解析 "2024-12-19" 或 "2024/12/19" 格式
            Pattern pattern2 = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
            Matcher matcher2 = pattern2.matcher(message);
            if (matcher2.find()) {
                int year = Integer.parseInt(matcher2.group(1));
                int month = Integer.parseInt(matcher2.group(2));
                int day = Integer.parseInt(matcher2.group(3));
                try {
                    LocalDate date = LocalDate.of(year, month, day);
                    return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                } catch (DateTimeParseException e) {
                    log.warn("日期解析失败: {}-{}-{}", year, month, day);
                }
            }
            
        } catch (Exception e) {
            log.error("解析日期失败", e);
        }
        
        return null;
    }
    
    /**
     * 解析科室
     */
    private String parseSection(String message) {
        // 常见科室关键词
        String[] sections = {"内科", "外科", "儿科", "妇科", "骨科", "眼科", "耳鼻喉科", 
                             "皮肤科", "口腔科", "神经内科", "神经外科", "心内科", "心外科",
                             "消化内科", "呼吸内科", "内分泌科", "泌尿外科", "肿瘤科", "急诊科"};
        
        for (String section : sections) {
            if (message.contains(section)) {
                return section;
            }
        }
        
        return null;
    }
}

