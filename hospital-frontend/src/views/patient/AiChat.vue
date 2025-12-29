<!--
 * AI智能分诊助手页面
-->
<template>
    <div class="ai-chat-container" :class="{ 'has-messages': messages.length > 0 }">
        <!-- 简洁的顶部标题（无消息时显示） -->
        <div v-if="messages.length === 0" class="welcome-header">
            <div class="welcome-icon">
                <i class="el-icon-service"></i>
            </div>
            <h2 class="welcome-title">今天有什么可以帮到你?</h2>
        </div>
        
        <!-- 聊天消息区域 -->
        <div v-if="messages.length > 0" class="chat-body" ref="chatBody">
            <div 
                v-for="(message, index) in messages" 
                :key="index"
                :class="['message', message.role]"
            >
                <div class="message-avatar">
                    <div class="avatar-wrapper">
                        <i v-if="message.role === 'user'" class="el-icon-user-solid"></i>
                        <i v-else class="el-icon-service"></i>
                    </div>
                </div>
                <div class="message-content">
                    <div class="message-text" v-html="formatMessage(message.content)"></div>
                    <div v-if="message.role === 'assistant' && message.content && message.content.trim() !== ''" class="disclaimer-in-message">
                        <i class="el-icon-warning"></i>
                        <span>本AI对话内容仅供参考，不构成医疗诊断，如有不适请及时就医</span>
                    </div>
                    <div class="message-time">{{ formatTime(message.time) }}</div>
                </div>
            </div>
            
            <!-- 加载中提示 -->
            <div v-if="loading" class="message assistant">
                <div class="message-avatar">
                    <i class="el-icon-service"></i>
                </div>
                <div class="message-content">
                    <div class="message-text typing">
                        <span></span>
                        <span></span>
                        <span></span>
                    </div>
                </div>
            </div>
        </div>
        
        <!-- 输入区域 -->
        <div class="chat-input-wrapper">
            <div class="chat-input" :class="{ 'has-messages': messages.length > 0 }">
                <div class="input-container">
                    <el-input
                        v-model="inputMessage"
                        type="textarea"
                        :rows="messages.length > 0 ? 2 : 6"
                        :placeholder="messages.length > 0 ? '继续对话...' : '给智能助手发送消息'"
                        @keyup.ctrl.enter="sendMessage"
                        @keyup.enter.exact="handleEnterKey"
                        :disabled="loading"
                        class="chat-textarea"
                        :autosize="messages.length === 0 ? { minRows: 6, maxRows: 10 } : false"
                    ></el-input>
                    
                    <div class="input-footer">
                        <div class="input-actions-left">
                            <button 
                                class="action-btn"
                                @click="clearChat"
                                title="清空对话"
                            >
                                <i class="el-icon-delete"></i>
                            </button>
                        </div>
                        
                        <button 
                            class="btn-send-circle"
                            @click="sendMessage"
                            :disabled="!inputMessage.trim() || loading"
                            :class="{ 'disabled': !inputMessage.trim() || loading }"
                            title="发送消息"
                        >
                            <i v-if="!loading" class="el-icon-top"></i>
                            <i v-else class="el-icon-loading"></i>
                        </button>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import request from "@/utils/request.js";

export default {
    name: "AiChat",
    data() {
        return {
            messages: [],
            inputMessage: "",
            loading: false,
            sessionId: "",
            eventSource: null
        };
    },
    mounted() {
        // 生成会话ID
        this.sessionId = this.generateSessionId();
    },
    beforeDestroy() {
        // 清理EventSource
        if (this.eventSource) {
            this.eventSource.close();
        }
    },
    methods: {
        generateSessionId() {
            return "session_" + Date.now() + "_" + Math.random().toString(36).substr(2, 9);
        },
        
        sendMessage() {
            if (!this.inputMessage.trim() || this.loading) {
                return;
            }
            
            const userMessage = this.inputMessage.trim();
            this.inputMessage = "";
            
            // 添加用户消息
            this.messages.push({
                role: "user",
                content: userMessage,
                time: new Date()
            });
            
            // 滚动到底部
            this.$nextTick(() => {
                this.scrollToBottom();
            });
            
            // 发送AI请求
            this.loading = true;
            this.sendStreamRequest(userMessage);
        },
        
        sendStreamRequest(message) {
            // 添加AI消息占位符
            const aiMessageIndex = this.messages.length;
            this.messages.push({
                role: "assistant",
                content: "",
                time: new Date()
            });
            
            // 使用EventSource接收流式响应
            // 使用相对路径，通过vue.config.js的proxy代理
            const url = `/ai/chat/stream?message=${encodeURIComponent(message)}&sessionId=${this.sessionId}`;
            
            console.log("连接AI服务:", url);
            
            this.eventSource = new EventSource(url);
            
            // 监听message事件（默认事件）
            this.eventSource.onmessage = (event) => {
                console.log("收到消息:", event.data);
                
                if (event.data === "DONE") {
                    console.log("收到完成标记");
                    this.loading = false;
                    if (this.eventSource) {
                        this.eventSource.close();
                        this.eventSource = null;
                    }
                    this.scrollToBottom();
                    return;
                }
                
                // 检查是否是错误消息
                if (event.data && (event.data.startsWith("错误:") || event.data.startsWith("抱歉"))) {
                    if (this.messages[aiMessageIndex].content === "") {
                        this.messages[aiMessageIndex].content = event.data;
                    } else {
                        this.messages[aiMessageIndex].content += "\n\n" + event.data;
                    }
                    this.loading = false;
                    if (this.eventSource) {
                        this.eventSource.close();
                        this.eventSource = null;
                    }
                    this.scrollToBottom();
                    return;
                }
                
                // 追加内容
                if (event.data && event.data.trim() !== "") {
                    this.messages[aiMessageIndex].content += event.data;
                    this.scrollToBottom();
                }
            };
            
            // 监听打开事件
            this.eventSource.onopen = () => {
                console.log("EventSource连接已打开");
            };
            
            // 错误处理
            this.eventSource.onerror = (error) => {
                console.error("EventSource错误:", error);
                console.error("EventSource状态:", this.eventSource.readyState);
                
                // 如果收到错误数据，显示错误消息
                if (error.data) {
                    if (this.messages[aiMessageIndex].content === "") {
                        this.messages[aiMessageIndex].content = error.data;
                    } else {
                        this.messages[aiMessageIndex].content += "\n\n" + error.data;
                    }
                    this.loading = false;
                    if (this.eventSource) {
                        this.eventSource.close();
                        this.eventSource = null;
                    }
                    this.scrollToBottom();
                    return;
                }
                
                // readyState: 0=CONNECTING, 1=OPEN, 2=CLOSED
                if (this.eventSource.readyState === EventSource.CLOSED) {
                    if (this.messages[aiMessageIndex].content === "") {
                        this.messages[aiMessageIndex].content = "连接已关闭。\n\n可能的原因：\n1. 后端服务未启动或端口不正确\n2. API Key配置错误\n3. 网络连接问题\n4. DeepSeek API服务异常\n\n请检查后端控制台日志获取详细错误信息。";
                    }
                    this.loading = false;
                    if (this.eventSource) {
                        this.eventSource.close();
                        this.eventSource = null;
                    }
                } else if (this.eventSource.readyState === EventSource.CONNECTING) {
                    // 正在重连，暂时不处理
                    console.log("EventSource正在重连...");
                }
            };
        },
        
        clearChat() {
            this.messages = [];
            if (this.eventSource) {
                this.eventSource.close();
                this.eventSource = null;
            }
            this.loading = false;
            
            // 清除后端会话
            if (this.sessionId) {
                request.post(`/ai/chat/clear?sessionId=${this.sessionId}`);
            }
            
            // 生成新会话ID
            this.sessionId = this.generateSessionId();
        },
        
        scrollToBottom() {
            this.$nextTick(() => {
                const chatBody = this.$refs.chatBody;
                if (chatBody) {
                    chatBody.scrollTop = chatBody.scrollHeight;
                }
            });
        },
        
        formatMessage(content) {
            // 简单的Markdown格式化
            if (!content) return "";
            
            return content
                .replace(/\n/g, "<br>")
                .replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>")
                .replace(/\*(.*?)\*/g, "<em>$1</em>");
        },
        
        formatTime(time) {
            if (!time) return "";
            const date = new Date(time);
            const hours = String(date.getHours()).padStart(2, "0");
            const minutes = String(date.getMinutes()).padStart(2, "0");
            return `${hours}:${minutes}`;
        },
        
        handleEnterKey(event) {
            // 单独按Enter时不发送，需要Ctrl+Enter
            if (!event.ctrlKey && !event.metaKey) {
                return;
            }
            this.sendMessage();
        }
    }
};
</script>

<style scoped lang="scss">
.ai-chat-container {
    display: flex;
    flex-direction: column;
    height: calc(100vh - 70px);
    max-width: 900px;
    margin: 0 auto;
    background: #fff;
    position: relative;
    
    &.has-messages {
        background: #f5f7fa;
    }
}

// 欢迎界面
.welcome-header {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 80px 20px 40px;
    text-align: center;
    
    .welcome-icon {
        width: 80px;
        height: 80px;
        background: linear-gradient(135deg, #409EFF 0%, #66b1ff 100%);
        border-radius: 24px;
        display: flex;
        align-items: center;
        justify-content: center;
        margin-bottom: 24px;
        box-shadow: 0 8px 24px rgba(64, 158, 255, 0.25);
        
        i {
            font-size: 40px;
            color: #fff;
        }
    }
    
    .welcome-title {
        font-size: 28px;
        font-weight: 600;
        color: #303133;
        margin: 0;
        letter-spacing: 0.5px;
    }
}

.chat-body {
    flex: 1;
    overflow-y: auto;
    padding: 30px 24px 20px;
    background: transparent;
    position: relative;
    
    // 自定义滚动条
    &::-webkit-scrollbar {
        width: 8px;
    }
    
    &::-webkit-scrollbar-track {
        background: rgba(0, 0, 0, 0.02);
        border-radius: 4px;
    }
    
    &::-webkit-scrollbar-thumb {
        background: rgba(64, 158, 255, 0.3);
        border-radius: 4px;
        
        &:hover {
            background: rgba(64, 158, 255, 0.5);
        }
    }
    
    .message {
        display: flex;
        margin-bottom: 28px;
        animation: fadeInUp 0.5s ease;
        
        .message-avatar {
            flex-shrink: 0;
            margin-right: 14px;
            
            .avatar-wrapper {
                width: 48px;
                height: 48px;
                border-radius: 14px;
                display: flex;
                align-items: center;
                justify-content: center;
                box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
                transition: all 0.3s ease;
                position: relative;
                
                &::after {
                    content: '';
                    position: absolute;
                    inset: -2px;
                    border-radius: 14px;
                    padding: 2px;
                    background: linear-gradient(135deg, rgba(255, 255, 255, 0.3), rgba(255, 255, 255, 0.1));
                    -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
                    -webkit-mask-composite: xor;
                    mask-composite: exclude;
                    opacity: 0;
                    transition: opacity 0.3s;
                }
                
                &:hover {
                    transform: scale(1.08) translateY(-2px);
                    box-shadow: 0 6px 16px rgba(0, 0, 0, 0.15);
                    
                    &::after {
                        opacity: 1;
                    }
                }
                
                i {
                    font-size: 24px;
                }
            }
        }
        
        .message-content {
            flex: 1;
            max-width: 75%;
            
            .message-text {
                background: #fff;
                padding: 16px 20px;
                border-radius: 16px;
                line-height: 1.75;
                word-wrap: break-word;
                box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
                border: 1px solid rgba(0, 0, 0, 0.04);
                font-size: 15px;
                color: #333;
                position: relative;
                transition: all 0.3s ease;
                
                &:hover {
                    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
                    transform: translateY(-1px);
                }
                
                // 代码块样式
                code {
                    background: #f5f7fa;
                    padding: 3px 8px;
                    border-radius: 4px;
                    font-size: 13px;
                    font-family: 'Courier New', monospace;
                    color: #409EFF;
                }
                
                strong {
                    font-weight: 600;
                    color: #2c3e50;
                }
                
                em {
                    color: #606266;
                    font-style: italic;
                }
            }
            
            .message-time {
                font-size: 12px;
                color: #909399;
                margin-top: 8px;
                padding: 0 6px;
                font-weight: 400;
            }
        }
        
        &.user {
            flex-direction: row-reverse;
            
            .message-avatar {
                margin-right: 0;
                margin-left: 14px;
                
                .avatar-wrapper {
                    background: linear-gradient(135deg, #409EFF 0%, #66b1ff 100%);
                    color: #fff;
                }
            }
            
            .message-content {
                text-align: right;
                
                .message-text {
                    background: linear-gradient(135deg, #409EFF 0%, #66b1ff 100%);
                    color: #fff;
                    border: none;
                    box-shadow: 0 4px 16px rgba(64, 158, 255, 0.35);
                    
                    &:hover {
                        box-shadow: 0 6px 20px rgba(64, 158, 255, 0.45);
                    }
                    
                    strong {
                        color: #fff;
                    }
                    
                    code {
                        background: rgba(255, 255, 255, 0.2);
                        color: #fff;
                    }
                }
                
                .message-time {
                    text-align: right;
                    color: rgba(255, 255, 255, 0.7);
                }
            }
        }
        
        &.assistant {
            .message-avatar {
                .avatar-wrapper {
                    background: linear-gradient(135deg, #67C23A 0%, #85CE61 100%);
                    color: #fff;
                }
            }
            
            .message-content .message-text {
                border-left: 3px solid #67C23A;
            }
        }
        
        .typing {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 16px 20px;
            background: #fff;
            border-radius: 16px;
            box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
            border-left: 3px solid #67C23A;
            
            span {
                width: 10px;
                height: 10px;
                border-radius: 50%;
                background: linear-gradient(135deg, #67C23A 0%, #85CE61 100%);
                animation: typing 1.4s infinite;
                
                &:nth-child(2) {
                    animation-delay: 0.2s;
                }
                
                &:nth-child(3) {
                    animation-delay: 0.4s;
                }
            }
        }
    }
}

// 消息中的免责声明
.disclaimer-in-message {
    margin-top: 12px;
    padding: 8px 12px;
    background: linear-gradient(135deg, #fff3cd 0%, #ffeaa7 100%);
    border-left: 3px solid #ffc107;
    border-radius: 4px;
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 10px;
    color: #ff9800;
    font-weight: 500;
    line-height: 1.4;
    width: 100%;
    box-sizing: border-box;
    
    i {
        font-size: 12px;
        color: #ff9800;
        flex-shrink: 0;
    }
    
    span {
        flex: 1;
    }
}

// 输入区域容器
.chat-input-wrapper {
    padding: 20px;
    background: transparent;
}

.chat-input {
    background: #fff;
    border-radius: 20px;
    border: 1px solid #e4e7ed;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
    transition: all 0.3s ease;
    
    &:hover {
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
    }
    
    &.has-messages {
        border-radius: 16px;
        border-top-left-radius: 16px;
        border-top-right-radius: 16px;
        border-bottom-left-radius: 0;
        border-bottom-right-radius: 0;
        border-bottom: none;
        box-shadow: 0 -2px 12px rgba(0, 0, 0, 0.08);
    }
    
    .input-container {
        position: relative;
        
        .chat-textarea {
            ::v-deep .el-textarea__inner {
                border: none;
                border-radius: 20px;
                padding: 20px 80px 20px 24px;
                font-size: 15px;
                line-height: 1.7;
                transition: all 0.3s ease;
                background: transparent;
                resize: none;
                min-height: 120px;
                
                &:focus {
                    border: none;
                    box-shadow: none;
                }
                
                &::placeholder {
                    color: #c0c4cc;
                    font-size: 15px;
                }
            }
        }
        
        .input-footer {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 12px 20px;
            border-top: 1px solid #f0f0f0;
            
            .input-actions-left {
                display: flex;
                gap: 8px;
                
                .action-btn {
                    width: 36px;
                    height: 36px;
                    border: none;
                    background: transparent;
                    border-radius: 8px;
                    color: #909399;
                    cursor: pointer;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    transition: all 0.2s ease;
                    
                    i {
                        font-size: 18px;
                    }
                    
                    &:hover {
                        background: #f5f7fa;
                        color: #409EFF;
                    }
                }
            }
            
            .btn-send-circle {
                width: 40px;
                height: 40px;
                border: none;
                border-radius: 50%;
                background: linear-gradient(135deg, #409EFF 0%, #66b1ff 100%);
                color: #fff;
                cursor: pointer;
                display: flex;
                align-items: center;
                justify-content: center;
                transition: all 0.3s ease;
                box-shadow: 0 2px 8px rgba(64, 158, 255, 0.3);
                
                i {
                    font-size: 18px;
                }
                
                &:hover:not(.disabled) {
                    transform: scale(1.1);
                    box-shadow: 0 4px 12px rgba(64, 158, 255, 0.4);
                }
                
                &:active:not(.disabled) {
                    transform: scale(0.95);
                }
                
                &.disabled {
                    background: #c0c4cc;
                    cursor: not-allowed;
                    box-shadow: none;
                    opacity: 0.6;
                }
            }
        }
    }
}

@keyframes fadeInUp {
    from {
        opacity: 0;
        transform: translateY(20px);
    }
    to {
        opacity: 1;
        transform: translateY(0);
    }
}

@keyframes typing {
    0%, 60%, 100% {
        transform: translateY(0);
        opacity: 0.6;
    }
    30% {
        transform: translateY(-8px);
        opacity: 1;
    }
}

// 响应式设计
@media (max-width: 768px) {
    .welcome-header {
        padding: 60px 20px 30px;
        
        .welcome-icon {
            width: 60px;
            height: 60px;
            
            i {
                font-size: 30px;
            }
        }
        
        .welcome-title {
            font-size: 22px;
        }
    }
    
    .chat-body {
        padding: 16px 12px;
        
        .message {
            .message-content {
                max-width: 85%;
            }
        }
    }
    
    .disclaimer-in-message {
        padding: 6px 10px;
        font-size: 9px;
        
        i {
            font-size: 11px;
        }
    }
    
    .chat-input-wrapper {
        padding: 12px;
    }
    
    .chat-input {
        .input-container {
            .chat-textarea {
                ::v-deep .el-textarea__inner {
                    padding: 16px 70px 16px 16px;
                    min-height: 100px;
                }
            }
            
            .input-footer {
                padding: 10px 16px;
            }
        }
    }
}
</style>
