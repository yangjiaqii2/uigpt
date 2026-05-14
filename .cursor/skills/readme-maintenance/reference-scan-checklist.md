# README scan checklist (UIGPT)

Run this checklist when refreshing `README.md`. Paths are repo-relative to the UIGPT root.

## 1. Repository layout

- List top-level dirs: `frontend/`, `backend/`, `docker/`, `.cursor/`, `skills/`, etc.
- Optional: `tree -L 2` or IDE file tree—do not duplicate entire trees in README; summarize.

## 2. Frontend stack

- **File**: `frontend/package.json`  
  Extract: `dependencies`, `devDependencies`, `scripts` (e.g. `predev`, `vite`).

## 3. Backend stack

- **File**: `backend/pom.xml`  
  Extract: Spring Boot parent version, `java.version`, notable starters (web, webflux client, JPA, validation), JWT, MySQL driver, COS, PDF/POI if present.

## 4. Runtime configuration

- **File**: `backend/src/main/resources/application.yml`  
  Note: datasource, `uigpt.*` API keys placeholders, Qdrant, COS, image/chat model properties.  
- **Templates**: `frontend/.env.example`; backend dotenv files if documented (no secret values).

## 5. Core Java entry points (adjust if renamed)

| Area | Typical files |
|------|----------------|
| Chat / SSE | `backend/src/main/java/top/uigpt/controller/ChatController.java`, `ConversationController.java`, `service/ChatService.java`, `service/ConversationService.java` |
| Image studio | `controller/ImageStudioController.java`, `controller/ImageStudioSessionController.java`, `service/ImageStudioGenerationPipeline.java`, related `imagestudio/` package |
| Video (if productized) | `controller/VideoStudioController.java` |
| RAG | `controller/RagAdminController.java`, `service/RagService.java` |
| Auth / user | `controller/AuthController.java`, `controller/MeController.java`, `service/JwtService.java` |
| Models admin | `controller/ChatModelsController.java`, prompt template controllers as needed |

## 6. Frontend structure

- **Routes**: `frontend/src/router/index.js` (or `router/*.js`).
- **Major views**: `frontend/src/views/*.vue` (e.g. chat, image gen, admin).

## 7. Database

- **Migrations / DDL**: `backend/src/main/resources/db/*.sql`—mention when README documents schema setup.

## 8. Consistency pass

- Cross-check README claims against the files above.
- Remove references to removed features; add new modules discovered in `controller/` and `views/`.
