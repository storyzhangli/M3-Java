# D30 SSH 环境取证原始记录

主机: 192.168.200.153 用户: root

## 主机名与系统

命令: `hostname; cat /etc/redhat-release 2>/dev/null; uname -r`

```text
chongba-docker
CentOS Linux release 7.9.2009 (Core)
3.10.0-1160.76.1.el7.x86_64
退出码: 0
```

## Docker 版本

命令: `docker version --format '{{.Server.Version}}' 2>/dev/null; docker --version`

```text
24.0.9
Docker version 24.0.9, build 2936816
退出码: 0
```

## Docker Compose 版本

命令: `docker compose version`

```text
Docker Compose version v2.24.7
退出码: 0
```

## Compose 目录

命令: `ls -la /root/pgvector-stack/`

```text
总用量 12
drwxr-xr-x.  3 root root   44 11月 13 11:01 .
dr-xr-x---. 11 root root 4096 11月 13 11:01 ..
-rw-r--r--.  1 root root  565 11月 13 11:03 docker-compose.yml
drwxr-xr-x.  2 root root   31 11月 13 11:01 init
退出码: 0
```

## 运行中容器

命令: `docker ps --format 'table {{.Names}}	{{.Image}}	{{.Status}}	{{.Ports}}'`

```text
NAMES           IMAGE                    STATUS                 PORTS
pgvector-pg18   pgvector/pgvector:pg18   Up 2 hours (healthy)   0.0.0.0:5432->5432/tcp, :::5432->5432/tcp
退出码: 0
```

## 全部容器

命令: `docker ps -a --format 'table {{.Names}}	{{.Image}}	{{.Status}}'`

```text
NAMES                 IMAGE                    STATUS
pgvector-pg18         pgvector/pgvector:pg18   Up 2 hours (healthy)
zl-minio              minio/minio              Exited (0) 2 years ago
zl-kibana-7140        kibana:7.14.0            Exited (137) 3 years ago
zl-es-7140            elasticsearch:7.14.0     Exited (137) 3 years ago
zl-kibana             kibana:7.4.2             Exited (0) 3 years ago
zl-es                 elasticsearch:7.4.2      Exited (143) 3 years ago
zl-zk                 wurstmeister/zookeeper   Exited (137) 3 years ago
flink_taskmanager_1   flink                    Exited (143) 4 years ago
flink_jobmanager_1    flink                    Exited (143) 4 years ago
zl-nginx              nginx                    Exited (128) 4 years ago
zl-tomcat             tomcat:latest            Exited (143) 4 years ago
peaceful_lewin        97d83a0c4f44             Exited (1) 5 years ago
myredis               redis                    Exited (0) 2 years ago
zl-mq                 rabbitmq:management      Exited (255) 3 years ago
zl-mysql              mysql:5.7                Exited (0) 2 years ago
退出码: 0
```

## 健康检查 pg_isready

命令: `docker exec pgvector-pg18 pg_isready -U postgres -d spring_ai`

```text
/var/run/postgresql:5432 - accepting connections
退出码: 0
```

## PostgreSQL 版本

命令: `docker exec pgvector-pg18 psql -U postgres -d spring_ai -t -c 'SELECT version();'`

```text
 PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 12.2.0-14+deb12u1) 12.2.0, 64-bit
退出码: 0
```

## pgvector 扩展版本

命令: `docker exec pgvector-pg18 psql -U postgres -d spring_ai -c "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"`

```text
 extname | extversion 
---------+------------
 vector  | 0.8.7
(1 row)
退出码: 0
```

## 可用扩展 pgvector

命令: `docker exec pgvector-pg18 psql -U postgres -d spring_ai -c "SELECT name, default_version, installed_version FROM pg_available_extensions WHERE name='vector';"`

```text
  name  | default_version | installed_version 
--------+-----------------+-------------------
 vector | 0.8.7           | 0.8.7
(1 row)
退出码: 0
```

## 容器最近日志

命令: `docker logs --tail 30 pgvector-pg18 2>&1`

```text

PostgreSQL Database directory appears to contain a database; Skipping initialization

2026-11-13 16:08:45.906 CST [1] LOG:  starting PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 12.2.0-14+deb12u1) 12.2.0, 64-bit
2026-11-13 16:08:45.909 CST [1] LOG:  listening on IPv4 address "0.0.0.0", port 5432
2026-11-13 16:08:45.909 CST [1] LOG:  listening on IPv6 address "::", port 5432
2026-11-13 16:08:45.913 CST [1] LOG:  listening on Unix socket "/var/run/postgresql/.s.PGSQL.5432"
2026-11-13 16:08:45.919 CST [32] LOG:  database system was shut down at 2026-11-13 16:08:44 CST
2026-11-13 16:08:45.924 CST [1] LOG:  database system is ready to accept connections
2026-11-13 16:13:45.925 CST [30] LOG:  checkpoint starting: time
2026-11-13 16:13:46.942 CST [30] LOG:  checkpoint complete: wrote 10 buffers (0.1%), wrote 3 SLRU buffers; 0 WAL file(s) added, 0 removed, 0 recycled; write=1.010 s, sync=0.003 s, total=1.018 s; sync files=6, longest=0.001 s, average=0.001 s; distance=57 kB, estimate=57 kB; lsn=0/2C704A8, redo lsn=0/2C70450
2026-11-13 16:23:46.036 CST [30] LOG:  checkpoint starting: time
2026-11-13 16:23:50.282 CST [30] LOG:  checkpoint complete: wrote 42 buffers (0.3%), wrote 1 SLRU buffers; 0 WAL file(s) added, 0 removed, 0 recycled; write=4.240 s, sync=0.002 s, total=4.247 s; sync files=32, longest=0.001 s, average=0.001 s; distance=232 kB, estimate=232 kB; lsn=0/2CAA7B8, redo lsn=0/2CAA728
2026-11-13 16:28:46.353 CST [30] LOG:  checkpoint starting: time
2026-11-13 16:28:49.994 CST [30] LOG:  checkpoint complete: wrote 36 buffers (0.2%), wrote 1 SLRU buffers; 0 WAL file(s) added, 0 removed, 0 recycled; write=3.634 s, sync=0.003 s, total=3.642 s; sync files=23, longest=0.002 s, average=0.001 s; distance=208 kB, estimate=230 kB; lsn=0/2CDE8D8, redo lsn=0/2CDE880
退出码: 0
```
