# 多阶段:node 构建 → nginx 静态托管(:8202,同源反代 /admin)。
# 构建期烘焙 Casdoor authority/client_id:
#   docker build --build-arg VITE_CASDOOR_CLIENT_ID=<id> --build-arg VITE_CASDOOR_AUTHORITY=http://localhost:8000 -t auth-console .
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
ARG VITE_CASDOOR_AUTHORITY=http://localhost:8000
ARG VITE_CASDOOR_CLIENT_ID=
ENV VITE_CASDOOR_AUTHORITY=$VITE_CASDOOR_AUTHORITY \
    VITE_CASDOOR_CLIENT_ID=$VITE_CASDOOR_CLIENT_ID \
    VITE_OIDC_SCOPE="openid profile offline_access"
RUN pnpm build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8202
