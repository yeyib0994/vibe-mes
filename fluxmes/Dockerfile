# FluxMES 前端镜像（Vite 静态产物 + nginx 反代 /api）
# 构建上下文 = fluxmes，需先执行: cd fluxmes && npm run build
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html

EXPOSE 80
