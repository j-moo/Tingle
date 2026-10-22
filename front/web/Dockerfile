# 빌드: docker build -t tingle-web . / 개발(/dev/ 경로) 빌드: --build-arg BASE=/dev/
FROM node:24-alpine AS build
WORKDIR /app
RUN npm install -g pnpm@11
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
ARG BASE=/
RUN pnpm build --base=$BASE

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
