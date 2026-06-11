# syntax=docker/dockerfile:1
# Custos 控制台镜像：多阶段——node 构建 Vue 产物，nginx 服静态 dist。宿主无需 Node。
# 默认走国内 npm 镜像加速（与项目根 Dockerfile 的 Aliyun maven 镜像姿态一致）；
# 海外构建可 --build-arg NPM_MIRROR=https://registry.npmjs.org/。
FROM node:20-alpine AS build
ARG NPM_MIRROR=https://registry.npmmirror.com
# VITE_API_BASE：构建期注入 axios baseURL；compose 经 nginx /api/ 反代时传 /api（同源免 CORS）。
ARG VITE_API_BASE=/api
ENV VITE_API_BASE=${VITE_API_BASE}
WORKDIR /app
RUN npm config set registry "$NPM_MIRROR"
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
