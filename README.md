<div align="center">

# Pombo IA

Entrega confiável de webhooks, com diagnóstico de falhas por IA.

Receba eventos pela API e entregue-os a qualquer URL com assinatura HMAC, retentativas controladas, Dead Letter Topic e histórico auditável. Quando uma entrega falha, a IA explica o motivo e sugere o que fazer.

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-KRaft-231F20?logo=apachekafka)
![Docker](https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-ready-326CE5?logo=kubernetes&logoColor=white)
![IA local](https://img.shields.io/badge/IA-local%20com%20Ollama-8A2BE2)
[![CI](https://github.com/tpsousa/PomboIA/actions/workflows/ci.yml/badge.svg)](https://github.com/tpsousa/PomboIA/actions/workflows/ci.yml)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

</div>

> Projeto em desenvolvimento. Este README descreve o produto que estou construindo. A seção [Status e roadmap](#status-e-roadmap) mostra o que já está pronto.

---

## O problema

Notificar outro sistema via webhook parece simples, até que o destino fica fora do ar, responde devagar ou recebe a mesma notificação duas vezes. Fazer a chamada HTTP dentro da requisição da API ainda acopla a sua disponibilidade à do cliente.

Quando a entrega falha, o desenvolvedor recebe uma lista de códigos (`401`, `503`, `TIMEOUT`) e precisa deduzir sozinho o que aconteceu.

O Pombo IA ataca as duas pontas.

Na entrega:
- Aceita o evento rapidamente (`202 Accepted`) e entrega de forma assíncrona.
- Faz retentativas com backoff exponencial e limite definido.
- Envia para um Dead Letter Topic as entregas esgotadas e permite o reenvio manual.
- Assina cada entrega com HMAC-SHA256, com proteção anti-replay.
- Guarda o histórico completo de tentativas (status HTTP, duração, erro).
- Usa Transactional Outbox, para que nenhum evento aceito se perca entre o banco e o Kafka.
- Protege o cadastro e a entrega contra SSRF.

No diagnóstico:
- Explica em linguagem natural por que uma entrega falhou e sugere o que fazer.
- Resolve os casos óbvios com regras e só chama o modelo quando elas não bastam.
- Funciona com modelo local (Ollama), sem API paga e sem enviar dados a terceiros.
- Expõe um servidor MCP para investigar entregas por conversa.
- É opcional: com a IA desligada, o Pombo funciona normalmente.

A garantia de entrega é at-least-once. O destinatário deve deduplicar por `X-Pombo-Delivery-Id`, que é o mesmo em todas as tentativas.

## Como funciona

```mermaid
flowchart LR
    C[Produtor] -->|POST /events| API

    subgraph APP[Pombo IA - Spring Boot]
        API[API REST] -->|1 transação| DB[(PostgreSQL)]
        OUT[Outbox Publisher] -->|lê pendentes| DB
        CONS[Delivery Consumer] -->|atualiza estado| DB
        INS[Módulo insights<br/>diagnóstico]
        INS -->|lê histórico| DB
    end

    OUT -->|publica| K[[Kafka<br/>retry topics + DLT]]
    K --> CONS
    CONS -->|POST assinado| D[Destinatário]
    INS -.->|opcional| LLM[Modelo local<br/>Ollama]
```

1. `POST /events` grava o evento, as entregas e as mensagens de outbox na mesma transação e responde `202`.
2. O publicador do outbox envia as mensagens ao Kafka (`acks=all`, producer idempotente).
3. O consumidor assume a entrega com uma transição de estado condicional (idempotência), faz o `POST` assinado e registra a tentativa.
4. Erro temporário vai para um retry topic com backoff. Erro permanente (`4xx`) marca a entrega como `FAILED`. Esgotadas as tentativas, ela vira `DEAD` e vai para o DLT.
5. Para qualquer entrega com falha, `GET /deliveries/{id}/diagnosis` devolve a explicação.

### Estados de uma entrega

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> IN_PROGRESS
    RETRYING --> IN_PROGRESS
    IN_PROGRESS --> DELIVERED: 2xx
    IN_PROGRESS --> RETRYING: erro temporário
    IN_PROGRESS --> FAILED: erro permanente
    RETRYING --> DEAD: tentativas esgotadas
    FAILED --> PENDING: redeliver
    DEAD --> PENDING: redeliver
```

---

## Como a IA funciona

### O papel da IA

A IA entra para resolver uma dor concreta: transformar o histórico técnico de uma entrega com falha em uma explicação que dá para agir em cima. Ela fica fora do caminho de entrega. Não decide retentativas, não altera estados e não vê o conteúdo dos eventos.

| A IA faz | A IA não faz |
|---|---|
| Classifica a causa provável da falha | Decidir se uma entrega deve ser retentada |
| Explica em português e sugere a ação | Ler ou enviar o payload dos eventos |
| Responde perguntas sobre entregas (via MCP) | Reenviar entregas sem confirmação humana |
| Resume o estado operacional | Detectar anomalias (isso fica com métricas e alertas) |

### Fluxo do diagnóstico

```mermaid
flowchart TD
    REQ[GET /deliveries/id/diagnosis] --> CACHE{Diagnóstico em cache<br/>para este estado?}
    CACHE -->|sim| RES[Resposta]
    CACHE -->|não| RULES[Motor de regras]
    RULES --> CONF{Causa clara?}
    CONF -->|sim| SAVE[Salva diagnóstico]
    CONF -->|"não (UNKNOWN ou<br/>resposta ambígua)"| FLAG{IA habilitada?}
    FLAG -->|não| SAVE
    FLAG -->|sim| LLM[Provider de modelo<br/>timeout curto]
    LLM -->|JSON válido| SAVE
    LLM -->|erro, timeout ou<br/>JSON inválido| SAVE
    SAVE --> RES
```

O módulo `com.pombo.insights` é isolado: só lê o histórico e não mexe no fluxo de entrega. Se ele falhar, o resto do sistema continua funcionando.

```
insights/
├── DiagnosisProvider              ← interface única
├── RuleBasedDiagnosisProvider     ← padrão: sem IA, sem custo
├── OllamaDiagnosisProvider        ← modelo local
├── HostedApiDiagnosisProvider     ← opcional, API externa
├── DiagnosisService               ← cache, fallback, métricas
├── DiagnosisInput                 ← somente metadados (ver "Privacidade e segurança")
└── mcp/                           ← servidor MCP (ferramentas somente leitura)
```

A escolha do provedor é configuração, não código:

```yaml
pombo:
  insights:
    enabled: true            # padrão: false
    provider: rules          # rules | ollama | hosted
    timeout: 10s
    include-response-snippet: false
```

### Motor de regras

Resolve os casos mais comuns de forma determinística e instantânea:

| Sinal no histórico | Categoria | Confiança |
|---|---|---|
| `401` / `403` em todas as tentativas | `AUTH_REJECTED` | Alta |
| `404` / `410` | `ENDPOINT_NOT_FOUND` | Alta |
| `429` | `RATE_LIMITED` | Alta |
| `5xx` recorrente | `TARGET_SERVER_ERROR` | Alta |
| `TIMEOUT` em 2 ou mais tentativas | `TARGET_TIMEOUT` | Alta |
| `DNS_FAILURE` | `DNS_FAILURE` | Alta |
| `CONNECTION_REFUSED` | `TARGET_DOWN` | Alta |
| `BLOCKED_TARGET` (proteção SSRF) | `BLOCKED_TARGET` | Alta |
| `400` / `422` com mensagem no corpo da resposta | `VALIDATION_REJECTED` | Média (o modelo ajuda a interpretar) |
| Padrão misto ou sem correspondência | `UNKNOWN` | Baixa (vai para o modelo, se habilitado) |

### Quando o modelo entra

O modelo só é chamado quando as regras não bastam:

1. Para interpretar a resposta do destinatário. Um `400` com `"invalid signature: timestamp too old"` vira algo como "o receptor rejeitou a assinatura por diferença de relógio; verifique a sincronização de horário".
2. Para explicar padrões mistos, por exemplo `503` seguido de `timeout` e depois `401`.
3. Para redigir a explicação e a ação sugerida de forma clara.

A saída do modelo é sempre JSON e é validada: a categoria precisa estar na lista fixa e os campos têm tamanho máximo. Se a resposta vier inválida, vazia ou fora do prazo, o sistema usa o resultado das regras. O modelo nunca é a única fonte de verdade.

### Contrato da API

`GET /api/v1/deliveries/{id}/diagnosis` (somente entregas `RETRYING`, `FAILED` ou `DEAD`)

```json
{
  "deliveryId": "3b9a8d2c-...",
  "category": "AUTH_REJECTED",
  "summary": "O destino respondeu 401 em todas as 5 tentativas.",
  "suggestedAction": "Verifique a validação de assinatura ou o token no receptor e use redeliver depois de corrigir.",
  "confidence": "HIGH",
  "source": "RULES",
  "model": null,
  "basedOnAttempts": 5,
  "generatedAt": "2026-01-10T14:12:00Z"
}
```

| `source` | Significado |
|---|---|
| `RULES` | Resolvido pelo motor de regras |
| `LLM` | Explicação gerada pelo modelo (campo `model` preenchido) |
| `RULES_FALLBACK` | O modelo falhou ou estava indisponível; resultado das regras |

Códigos de resposta: `200`, `404` (entrega inexistente ou de outro usuário), `409` (entrega sem falha para diagnosticar) e `503` (recurso desabilitado).

### Cache

Os diagnósticos ficam na tabela `delivery_diagnoses`, com chave `(delivery_id, attempt_count)`. Uma entrega finalizada não muda, então o diagnóstico é calculado uma vez. Se surgir uma nova tentativa, o cache daquele estado deixa de valer. Isso reduz custo, latência e carga no modelo.

### Privacidade e segurança

| Risco | Decisão |
|---|---|
| Vazamento de dados do cliente | O modelo recebe somente metadados: status, contagem de tentativas, códigos HTTP, tempos, códigos de erro e o host do destino. Nunca recebe o payload do evento, o segredo, nem o caminho e a query da URL. |
| Trecho da resposta do destinatário | Desligado por padrão (`include-response-snippet: false`). Quando ligado, é truncado, sanitizado e tratado como dado não confiável. |
| Prompt injection | O texto vindo de servidores externos fica em um bloco delimitado e marcado como dado. O modelo não executa ações, e a saída é validada contra um esquema fixo. |
| Isolamento entre usuários | O diagnóstico usa o usuário autenticado. Nenhuma ferramenta recebe `userId` escolhido pelo modelo. |
| Custo e abuso | Limite de requisições por usuário, timeout curto e cache. |
| Dependência do modelo | Recurso desligado por padrão, com fallback para regras em qualquer falha. |
| Chaves de API | Quando houver provedor externo, a chave vem de variável de ambiente ou do Secrets Manager, nunca do repositório. |

### Modelo local com Ollama

O Ollama roda na sua máquina, sem chave e sem cobrança, e nenhum dado sai do ambiente. Ele entra no Compose como perfil opcional:

```bash
docker compose --profile ai up -d
docker compose exec ollama ollama pull <modelo>      # escolha um modelo pequeno (3B a 8B)
```

```yaml
pombo:
  insights:
    enabled: true
    provider: ollama
    ollama:
      base-url: http://ollama:11434
      model: <modelo>
```

Um modelo pequeno basta para classificar falhas e redigir a explicação. Consulte a biblioteca do Ollama para ver as opções. O consumo de memória e CPU depende do modelo escolhido, por isso a IA local não faz parte do perfil econômico da AWS: lá o provedor é `rules`.

### Agente via MCP

O Pombo IA expõe um servidor MCP para que um assistente investigue entregas por conversa ("por que o pedido 1042 não chegou?"). Ele automatiza o roteiro do [runbook](docs/runbook.md).

| Ferramenta | Tipo | Ação |
|---|---|---|
| `get_delivery(id)` | Leitura | Estado e tentativas da entrega |
| `list_failed_deliveries(...)` | Leitura | Entregas com falha recentes do usuário |
| `diagnose_delivery(id)` | Leitura | Diagnóstico descrito na seção anterior |
| `get_outbox_stats()` | Leitura (`ADMIN`) | Fila pendente e idade da mais antiga |
| `propose_redeliver(id)` | Proposta | Prepara o reenvio e devolve um código de confirmação |
| `confirm_redeliver(code)` | Escrita | Executa o reenvio somente após confirmação humana explícita |

Regras do servidor MCP:
- Usa o JWT do usuário, então cada ferramenta enxerga apenas os dados dele.
- Nenhuma ferramenta recebe `userId` como parâmetro.
- Ações de escrita exigem um segundo passo com código de curta duração.
- Todas as chamadas são registradas em log e métrica.

### Qualidade da IA

Para saber se o diagnóstico é bom, e para comparar provedores, o projeto terá um conjunto de casos de avaliação em `src/test/resources/insights/cases.json`: históricos de entrega sintéticos com a categoria esperada.

- Motor de regras: testes unitários exaustivos, sem modelo.
- Provider de modelo: testado com um stub, validando a checagem de JSON, o fallback e o timeout. O CI nunca chama um modelo real.
- Avaliação manual: um comando executa os casos contra o Ollama e mostra a taxa de acerto por categoria.

### Observabilidade da IA

| Métrica | Descrição |
|---|---|
| `pombo.insights.requests{source,outcome}` | Diagnósticos por origem e resultado |
| `pombo.insights.duration` | Latência por provedor |
| `pombo.insights.cache.hits` | Aproveitamento do cache |
| `pombo.insights.fallbacks` | Vezes em que o modelo falhou e as regras assumiram |
| `pombo.insights.tokens` | Tokens usados (apenas provedores que informam) |

---

## Stack

| Área | Tecnologias |
|---|---|
| Backend | Java 21, Spring Boot 4, Spring Web, Spring Data JPA, Spring Security (JWT), Bean Validation |
| Dados | PostgreSQL, Flyway |
| Mensageria | Apache Kafka, Spring for Apache Kafka (`@RetryableTopic`, DLT) |
| IA | Ollama (modelo local), MCP, camada própria de provedores. Spring AI será avaliado se for compatível com a versão do Spring Boot em uso |
| Qualidade | JUnit 5, Mockito, Testcontainers, WireMock, ArchUnit |
| Observabilidade | Actuator, Micrometer, Prometheus, Grafana, logs JSON |
| Infra | Docker, Docker Compose, Kubernetes (Kind), Terraform, AWS, GitHub Actions (OIDC) |

## Executando localmente

Pré-requisitos: Docker 24+ e Docker Compose v2.

```bash
git clone https://github.com/tpsousa/PomboIA.git
cd PomboIA
cp .env.example .env          # defina DB_PASSWORD, JWT_SECRET e SECRETS_ENC_KEY
docker compose up -d --build
```

A API sobe em `http://localhost:8080` e a documentação interativa em `http://localhost:8080/swagger-ui.html`.

Perfis opcionais do Compose:

```bash
docker compose --profile tools up -d    # destinatário de teste + Kafka UI
docker compose --profile ai up -d       # Ollama (IA local)
```

Para encerrar:

```bash
docker compose down        # mantém os dados
docker compose down -v     # apaga os volumes
```

## Experimente o fluxo completo

```bash
# 1. Criar conta e obter token
curl -s -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@exemplo.com","password":"uma-senha-forte-123"}'

TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@exemplo.com","password":"uma-senha-forte-123"}' | jq -r .accessToken)

# 2. Cadastrar um destino (o segredo é exibido apenas nesta resposta)
curl -s -X POST localhost:8080/api/v1/endpoints \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"http://receiver:8080/hook","eventTypes":["*"]}'

# 3. Enviar um evento
curl -s -X POST localhost:8080/api/v1/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-1042' \
  -d '{"type":"pedido.pago","payload":{"pedidoId":1042,"valor":"199.90"}}'

# 4. Consultar a entrega e suas tentativas
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/deliveries/<DELIVERY_ID> | jq

# 5. Se a entrega falhou, pedir o diagnóstico
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/deliveries/<DELIVERY_ID>/diagnosis | jq
```

> `http` e destinos de rede interna só são aceitos no perfil `local`. Em produção, apenas `https` e IPs públicos.

### Verificando a assinatura (lado do destinatário)

```
X-Pombo-Signature: v1=<hex>
hex = HMAC_SHA256(secret, X-Pombo-Timestamp + "." + corpoBruto)
```

Rejeite timestamps com mais de 5 minutos de diferença e deduplique por `X-Pombo-Delivery-Id`.

## API

| Método | Caminho | Descrição |
|---|---|---|
| POST | `/api/v1/auth/register` · `/auth/login` | Cadastro e login (JWT, 15 min) |
| POST/GET/PATCH | `/api/v1/endpoints` | Gerenciar destinos |
| POST | `/api/v1/endpoints/{id}/rotate-secret` | Rotacionar segredo de assinatura |
| POST | `/api/v1/events` | Enviar evento (`202`, suporta `Idempotency-Key`) |
| GET | `/api/v1/events` · `/events/{id}` | Consultar eventos |
| GET | `/api/v1/deliveries` · `/deliveries/{id}` | Entregas e histórico de tentativas |
| GET | `/api/v1/deliveries/{id}/diagnosis` | Diagnóstico da falha |
| POST | `/api/v1/deliveries/{id}/redeliver` | Reenviar entrega `FAILED`/`DEAD` |
| GET | `/api/v1/admin/stats` | Indicadores operacionais (`ADMIN`) |

Erros seguem o padrão RFC 7807 (`application/problem+json`). Contratos completos e exemplos em [`docs/SPEC.md`](docs/SPEC.md#8-api-rest).

## Testes

```bash
./mvnw test      # unitários e integração (requer Docker para o Testcontainers)
```

A cobertura é focada nos fluxos críticos: sucesso, retry até sucesso, `DEAD` com DLT, erro permanente, mensagem Kafka duplicada, Kafka indisponível durante o envio, isolamento entre usuários, proteção SSRF e o comportamento do diagnóstico (regras, fallback e validação da saída do modelo).

## Kubernetes

Os manifests ficam em [`k8s/`](k8s), com startup/liveness/readiness probes, requests/limits e rolling update sem indisponibilidade.

```bash
kind create cluster --name pombo
kind load docker-image pombo:dev --name pombo
kubectl apply -f k8s/
kubectl port-forward svc/pombo 8080:80
```

Uma decisão que vale explicar: a readiness considera o PostgreSQL, mas não o Kafka nem o modelo de IA. Com eles fora do ar, a API continua aceitando eventos. O estado de cada dependência é acompanhado por métricas e alertas, não por probes.

## AWS

| Perfil | Composição | Uso |
|---|---|---|
| Econômico | ECR, EC2 com Compose, SSM Parameter Store, CloudWatch, IAM (OIDC) | Demonstração padrão. IA em modo `rules` |
| Completo (efêmero) | EKS, RDS, MSK, ALB via Terraform | Demonstrações pontuais, seguido de `terraform destroy` |

A infraestrutura é definida em [`terraform/`](terraform). O deploy via GitHub Actions usa OIDC, sem chaves AWS permanentes no repositório.

> Atenção: EKS, MSK, RDS, NAT Gateway e load balancers geram custo mesmo ociosos. Configure o AWS Budgets antes do primeiro `apply` e destrua o ambiente completo após o uso. A IA local (Ollama) não roda no perfil econômico.

## Observabilidade

- Logs JSON com `requestId`, `deliveryId` e `attempt`.
- `/actuator/health/liveness`, `/actuator/health/readiness` e `/actuator/prometheus`.
- Métricas de tentativas por resultado, duração das entregas, entregas `DEAD`, mensagens pendentes no outbox e idade da mais antiga, lag do consumidor Kafka e métricas de diagnóstico.
- Dashboard Grafana opcional em [`observability/`](observability).

O procedimento para investigar uma entrega "aceita mas não concluída" está em [`docs/runbook.md`](docs/runbook.md).

## Estrutura do repositório

```
PomboIA/
├── src/main/java/com/pombo/   auth · user · endpoint · event · delivery · outbox · insights · shared
├── src/main/resources/db/migration/
├── src/test/
├── k8s/            manifests Kubernetes
├── terraform/      infraestrutura AWS
├── observability/  Prometheus e Grafana
├── load-test/      cenários k6
├── docs/           SPEC.md · runbook.md · cost-notes.md
├── .github/workflows/   ci.yml · release.yml
├── Dockerfile
└── docker-compose.yml
```

> O nome técnico (pacote `com.pombo`, cabeçalhos `X-Pombo-*`, tópicos `pombo.*`) continua sendo `pombo`. Pombo IA é o nome do produto e do repositório.

## Documentação

| Documento | Conteúdo |
|---|---|
| [`docs/SPEC.md`](docs/SPEC.md) | Especificação completa: requisitos, regras de negócio, modelo de dados, estratégia Kafka, segurança, CI/CD, roadmap |
| [`docs/runbook.md`](docs/runbook.md) | Troubleshooting e comandos de diagnóstico |
| [`docs/cost-notes.md`](docs/cost-notes.md) | Premissas de custo na AWS |

## Status e roadmap

| Fase | Descrição | Status |
|---|---|---|
| 1 | Domínio, entidades, migrations e banco local | Em andamento: falta fechar os testes de integração |
| 2 | API REST e persistência (destinos, eventos, entregas) | Em andamento: destinos prontos, eventos em seguida |
| 3 | Segurança (JWT, papéis, SSRF) | Planejado |
| 4 | Kafka: outbox, consumidor, retry e DLT | Planejado |
| 5 | Testes e cenários de falha (MVP) | Planejado |
| 6 | Docker e ambiente local completo | Planejado |
| 7 | Health checks e observabilidade | Planejado |
| 8 | CI/CD | Planejado |
| 9 | Kubernetes local | Planejado |
| 10 | AWS e Terraform | Planejado |
| 11 | Testes de carga e documentação final | Planejado |
| 12 | Diagnóstico por regras: módulo `insights`, endpoint, cache e métricas | Planejado |
| 13 | Provedor de IA local (Ollama), validação de saída e conjunto de avaliação | Planejado |
| 14 | Servidor MCP com ferramentas de leitura e reenvio com confirmação | Planejado |

As fases 12 a 14 começam depois do MVP (fase 5), porque dependem de entregas reais e do histórico de tentativas.

O acompanhamento detalhado está no [GitHub Projects](https://github.com/users/tpsousa/projects/1).

## Limitações conhecidas

- A entrega é at-least-once, sem ordenação entre eventos de um mesmo destino.
- Kafka e PostgreSQL rodam em nó único em desenvolvimento e no perfil econômico.
- Não há multi-tenancy, rate limiting por destino nem revogação de JWT antes da expiração.
- O diagnóstico por modelo depende da qualidade do modelo local escolhido e pode errar. Por isso o resultado informa a origem (`source`) e a confiança.
- A IA local exige memória e CPU adicionais e não está prevista no perfil econômico da AWS.

A lista completa e as evoluções planejadas estão em [`docs/SPEC.md`](docs/SPEC.md#22-limitações-conhecidas).

## Licença

Distribuído sob a licença MIT. Veja [`LICENSE`](LICENSE).
