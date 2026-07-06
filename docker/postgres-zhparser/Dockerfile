# pgvector + zhparser（中文分词全文检索）二合一镜像
# SCWS + zhparser 编译步骤参照 zhparser 官方仓库 docker/bookworm/16/Dockerfile 适配到 pg17。
FROM pgvector/pgvector:pg17

ARG DEBIAN_FRONTEND=noninteractive

# deb.debian.org 在国内网络下频繁 502（apt.postgresql.org 反而稳定），换阿里云镜像规避。
RUN set -eux; \
    sed -i 's#http://deb.debian.org/debian-security#http://mirrors.aliyun.com/debian-security#; s#http://deb.debian.org/debian#http://mirrors.aliyun.com/debian#' \
        /etc/apt/sources.list.d/debian.sources

# 拆成独立层：apt 源偶发 502 时只需重试对应层，不必重新下载已成功的包。
# Acquire::Retries 让 apt 对单个包的瞬时网络错误自动重试，减少整层失败概率。
RUN set -eux; \
    apt-get update -o Acquire::Retries=5; \
    apt-get install -y --no-install-recommends -o Acquire::Retries=5 \
        ca-certificates build-essential git pkg-config automake autoconf libtool \
        postgresql-server-dev-${PG_MAJOR}

RUN set -eux; \
    git clone --branch 1.2.3 --single-branch --depth 1 https://github.com/hightman/scws.git /tmp/scws; \
    cd /tmp/scws; \
    touch README; aclocal; autoconf; autoheader; libtoolize; automake --add-missing; \
    ./configure; \
    make install

RUN set -eux; \
    git clone --branch v2.3 --single-branch --depth 1 https://github.com/amutu/zhparser.git /tmp/zhparser; \
    cd /tmp/zhparser; \
    make install

RUN set -eux; \
    apt-get purge -y --auto-remove build-essential git pkg-config automake autoconf libtool postgresql-server-dev-${PG_MAJOR}; \
    rm -rf /var/lib/apt/lists/* /tmp/scws /tmp/zhparser
