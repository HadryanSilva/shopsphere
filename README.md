# ShopSphere

Projeto educacional de e-commerce distribuído em microsserviços Java/Spring.
O código da aplicação, os contratos compartilhados e a infraestrutura de
desenvolvimento são mantidos num único repositório Git, com raiz nesta pasta.

## Organização do monorepo

| Caminho | Responsabilidade |
|---|---|
| `api-gateway/` | Entrada HTTP e roteamento com Spring Cloud Gateway MVC |
| `catalog-service/` | Produtos e categorias |
| `cart-service/` | Carrinho e quantidades por cliente |
| `order-service/` | Checkout, pedidos e snapshots financeiros |
| `inventory-service/` | Estrutura inicial para evolução de estoque |
| `payment-service/` | Estrutura inicial para evolução de pagamentos |
| `contracts/` | Contratos e fixtures usados pelos serviços e testes |
| `compose.yml`, `init.sql` | PostgreSQL e Keycloak para desenvolvimento local |

Cada serviço mantém o seu próprio build Gradle, wrapper, dependências e testes.
O monorepo não introduz um build Gradle único nem dependências de código ou de
banco entre serviços. Execute os comandos a partir do diretório do módulo.

## Build e testes

Os builds atuais configuram Java 25. Por exemplo, em PowerShell:

```powershell
Set-Location catalog-service
.\gradlew.bat test --no-daemon
```

Em Linux/macOS, use `./gradlew test --no-daemon` no mesmo módulo. O wrapper
pode precisar baixar a distribuição Gradle e as dependências no primeiro uso.

Os testes que precisam de PostgreSQL são ativados explicitamente por variável
de ambiente. Consulte [TESTING.md](TESTING.md) e o `TESTING.md` do serviço antes
de executar integrações. Mantenha os módulos ao lado de `contracts/`: alguns
testes leem fixtures compartilhadas usando caminhos relativos.

## Desenvolvimento local

O Compose atual inicia PostgreSQL e Keycloak, sem iniciar os microsserviços:

```powershell
docker compose up -d
```

As configurações locais dos serviços de domínio e Gateway ficam nos perfis
`application-dev.yaml`. As credenciais de desenvolvimento do Compose não são
configurações de produção. Não versione arquivos `.env` com valores reais,
credenciais, caches, diretórios de build ou configurações pessoais de IDE.
Os arquivos `.env.example` podem documentar variáveis sem segredos.

## Build e deploy independentes

Compartilhar o repositório não exige publicar todos os serviços juntos. Cada
serviço deve produzir seu próprio artefato/imagem e poder ser implantado de
forma independente. Alterações em contratos devem validar também os serviços
que os fornecem e consomem.

Dockerfiles, pipelines de CI/CD e deployment remoto ainda não fazem parte
desta configuração. Não foram adicionados nesta adoção do monorepo. Quando
existir um ambiente remoto, a configuração de infraestrutura, deployment por
ambiente, dashboards, alertas e runbooks poderá ir para um repositório
`shopsphere-platform`, conforme a necessidade operacional. A instrumentação
dos serviços e suas migrations continuam junto do código de cada serviço.

## Documentação

- [AGENTS.md](AGENTS.md): regras para agentes de código.
- [SHOPSPHERE_CODEX_CONTEXT.md](SHOPSPHERE_CODEX_CONTEXT.md): contexto e propostas;
  confirmar a implementação real antes de usar como inventário.
- [BACKLOG.md](BACKLOG.md): entregas e pendências.
- [contracts/README.md](contracts/README.md): contratos compartilhados.
- [TESTING.md](TESTING.md): execução dos testes e infraestrutura necessária.
