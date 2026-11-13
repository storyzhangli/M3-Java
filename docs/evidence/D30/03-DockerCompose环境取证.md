# D30 证据 03：Docker Compose 环境取证

> 取证方式：经 SSH（paramiko）连接 `192.168.200.153` 执行命令，原始记录见 `logs/D30/03-ssh-environment.md`。
> 全程只读取证，未执行升级、重装、重建、删除等操作。

## 一、主机与 Docker 版本（真实输出）

| 项 | 真实值 |
| --- | --- |
| 主机名 | `chongba-docker` |
| 操作系统 | CentOS Linux 7.9.2009（Core） |
| 内核 | `3.10.0-1160.76.1.el7.x86_64` |
| Docker Engine | `24.0.9`（build 2936816） |
| Docker Compose | `v2.24.7`（使用 `docker compose`，非 `docker-compose`） |

## 二、Compose 目录与编排文件

目录 `/root/pgvector-stack/` 包含 `docker-compose.yml` 与 `init/`。
`docker-compose.yml` 真实内容（密码已脱敏，不入库真实值）：

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg18
    container_name: pgvector-pg18
    restart: unless-stopped
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ********（环境变量注入，已脱敏）
      POSTGRES_DB: spring_ai
      TZ: Asia/Shanghai
    volumes:
      - pg18_data:/var/lib/postgresql
      - ./init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d spring_ai"]
      interval: 5s
      timeout: 3s
      retries: 10
    shm_size: 256mb

volumes:
  pg18_data:
```

初始化脚本 `init/01-extensions.sql`：

```sql
-- Auto-run on first container initialization
\c spring_ai
CREATE EXTENSION IF NOT EXISTS vector;
```

## 三、容器状态（真实输出）

```text
NAMES           IMAGE                    STATUS                 PORTS
pgvector-pg18   pgvector/pgvector:pg18   Up 2 hours (healthy)   0.0.0.0:5432->5432/tcp, :::5432->5432/tcp
```

- 容器 `pgvector-pg18` 处于 **Up (healthy)**，端口映射 `0.0.0.0:5432->5432`。
- 主机上另有 14 个历史容器（minio/kibana/es/zk/flink/mysql 等），均为 Exited，**未触碰**。

## 四、宿主侧连通性

- ICMP（`Test-Connection`）无响应（ICMP 被禁），不代表不可达；
- `Test-NetConnection -Port 5432` 结果 **TcpTestSucceeded = True**，证明 TCP 层真实可达。

## 五、结论

Docker Compose 编排真实存在且容器健康运行，端口、库名、时区、数据卷、健康检查、初始化扩展均与任务书基线一致；
取证过程未对环境做任何变更，真实密码已在证据中脱敏。
