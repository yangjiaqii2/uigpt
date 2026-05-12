# 全栈项目构建技能

## 技能描述

此技能用于指导AI在一个空文件夹中创建完整的前后端分离项目：Vue3前端 + Java后端。

## 使用前提

- 当前文件夹为空
- AI具备Vue3和Java Spring Boot知识

## 执行步骤

### 第一阶段：项目初始化

1. **确认工作目录**
   - 确认当前为空文件夹
   - 询问用户项目名称（默认使用当前文件夹名）

2. **创建目录结构**
   project-root/
   ├── frontend/ # Vue3前端项目
   ├── backend/ # Java后端项目
   ├── docs/ # 项目文档
   └── README.md # 项目说明

### 第二阶段：后端构建（Java Spring Boot）

#### 2.1 项目配置

- 使用 Spring Boot 3.x + JDK 17
- 构建工具：Maven
- 依赖项：
- Spring Web
- Spring Boot DevTools
- Lombok
- 可选：MyBatis Plus / JPA
- 可选：MySQL Driver

#### 2.2 创建核心文件结构

backend/
├── pom.xml
├── src/
│ ├── main/
│ │ ├── java/top/uigpt/
│ │ │ ├── UigptApplication.java
│ │ │ ├── controller/
│ │ │ ├── service/
│ │ │ ├── entity/
│ │ │ └── config/
│ │ └── resources/
│ │ ├── application.yml
│ │ └── static/
│ └── test/
└── README.md

#### 2.3 生成基础代码

- Spring Boot 启动类
- 示例REST API（如：`/api/login`）
- 跨域配置（允许前端调用）
- application.yml 配置文件（端口：8080）

### 第三阶段：前端构建（Vue3）

#### 3.1 项目初始化命令

```bash
# 使用Vite创建Vue3项目
npm create vite@latest frontend -- --template vue
cd frontend
npm install

3.2 安装必要依赖

npm install axios        # HTTP请求
npm install vue-router   # 路由
npm install pinia        # 状态管理
npm install element-plus # UI组件库（可选）

3.3 创建核心文件结构

frontend/
├── src/
│   ├── api/           # API接口
│   ├── components/    # 组件
│   ├── views/         # 页面
│   ├── router/        # 路由配置
│   ├── stores/        # Pinia状态
│   ├── App.vue
│   └── main.js
├── vite.config.js     # Vite配置（含代理）
├── package.json
└── index.html

3.4 生成基础代码
main.js 入口文件（引入router、pinia、element-plus）

App.vue 根组件

Vite代理配置（代理 /api 到 http://localhost:8080）

第四阶段：前后端联调

1.启动后端
cd backend
mvn spring-boot:run

2.启动前端
cd frontend
npm run dev

验证连接

前端默认地址：http://localhost:5173

后端默认地址：http://localhost:8080

验证 API 调用是否成功



第五阶段：提供业务逻辑
询问用户具体业务需求，然后：

创建对应的Entity、Controller、Service

创建前端对应的views和components

实现CRUD操作

添加数据验证

添加错误处理

技能输出规范
当AI执行此技能时，应：

逐文件生成 - 不要一次性输出所有代码，逐个文件生成并说明

提供完整代码 - 每个文件给出可直接复制使用的完整代码

附带说明 - 每个关键文件附带简短说明

命令可执行 - 提供的命令确保可直接复制执行

错误处理 - 提示常见的错误和解决方案

页面中不要出现任何的英文报错像(Request failed with status code 502) 要改为中文显示报错信息

```
