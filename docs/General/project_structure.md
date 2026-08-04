# Project Structure

## KTMP_NEXUS_BACKEND (Backend)
```
KTMP_NEXUS_BACKEND (Backend)/
├── .github/
│   └── workflows/
│       ├── ci.yml
│       └── deploy-aws.yml
├── config/
│   ├── auth.config.ts
│   ├── db.config.js
│   ├── email.config.ts
│   └── upload.config.ts
├── infra/
│   ├── nginx/
│   │   └── nginx.conf
│   ├── scripts/
│   │   └── pull-ssm-params.sh
│   └── terraform/
│       ├── main.tf
│       └── terraform.tfvars.example
├── packages/
│   └── shared/
│       ├── src/
│       ├── package-lock.json
│       ├── package.json
│       └── tsconfig.json
├── protos/
│   ├── group.proto
│   ├── user.proto
│   └── workspace.proto
├── services/
│   ├── api-gateway/
│   │   ├── src/
│   │   ├── .env.example
│   │   ├── Dockerfile
│   │   ├── package-lock.json
│   │   ├── package.json
│   │   └── tsconfig.json
│   ├── file-service/
│   │   ├── prisma/
│   │   ├── src/
│   │   ├── tests/
│   │   ├── .env
│   │   ├── .env.example
│   │   ├── Dockerfile
│   │   ├── package-lock.json
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   └── vitest.config.ts
│   ├── identity-service/
│   │   ├── prisma/
│   │   ├── src/
│   │   ├── .env.example
│   │   ├── Dockerfile
│   │   ├── package-lock.json
│   │   ├── package.json
│   │   ├── test-db.js
│   │   └── tsconfig.json
│   ├── messaging-service/
│   │   ├── prisma/
│   │   ├── proto/
│   │   ├── src/
│   │   ├── .env
│   │   ├── .env.example
│   │   ├── Dockerfile
│   │   ├── package-lock.json
│   │   ├── package.json
│   │   ├── test-prisma.ts
│   │   └── tsconfig.json
│   ├── notification-service/
│   │   ├── prisma/
│   │   ├── src/
│   │   ├── .env
│   │   ├── .env.example
│   │   ├── Dockerfile
│   │   ├── package-lock.json
│   │   ├── package.json
│   │   ├── test-notifs.cjs
│   │   ├── test-notifs.js
│   │   └── tsconfig.json
│   └── ws-gateway/
│       ├── src/
│       ├── .env.example
│       ├── Dockerfile
│       ├── package-lock.json
│       ├── package.json
│       └── tsconfig.json
├── src/
│   └── app.ts
├── .dockerignore
├── .env
├── .env copy.example
├── .env.docker
├── .env.docker.example
├── .env.example
├── .gitignore
├── docker-compose.prod.yml
├── docker-compose.yml
├── fix_schema.sql
├── package-lock.json
├── package.json
├── README.md
└── tsconfig.json
```

## KTMP_NEXUS_FRONTEND (Web Frontend)
```
KTMP_NEXUS_FRONTEND (Web Frontend)/
├── app/
│   ├── (app)/
│   │   └── layout.tsx
│   ├── account/
│   │   └── page.tsx
│   ├── admin/
│   │   ├── audit/
│   │   ├── dashboard/
│   │   ├── documents/
│   │   ├── invitations/
│   │   ├── roles/
│   │   ├── settings/
│   │   ├── users/
│   │   ├── layout.tsx
│   │   └── page.tsx
│   ├── ai/
│   │   ├── layout.tsx
│   │   └── page.tsx
│   ├── auth/
│   │   ├── forgot-password/
│   │   ├── sign-in/
│   │   ├── sign-up/
│   │   └── verify-email/
│   ├── chat/
│   │   ├── [id]/
│   │   ├── layout.tsx
│   │   └── page.tsx
│   ├── components/
│   │   ├── layout/
│   │   ├── ClientLayoutWrapper.tsx
│   │   └── RootProvider.tsx
│   ├── dashboard/
│   │   └── page.tsx
│   ├── invite/
│   │   └── page.tsx
│   ├── join/
│   │   └── [id]/
│   ├── knowledge/
│   │   ├── [docId]/
│   │   └── page.tsx
│   ├── login/
│   │   └── page.tsx
│   ├── register/
│   │   └── page.tsx
│   ├── security/
│   │   ├── audit-logs/
│   │   └── page.tsx
│   ├── settings/
│   │   └── page.tsx
│   ├── types/
│   │   └── types.ts
│   ├── wiki/
│   │   ├── [slug]/
│   │   ├── components/
│   │   ├── new/
│   │   ├── plans/
│   │   ├── review/
│   │   └── page.tsx
│   ├── workspace/
│   │   └── settings/
│   ├── favicon.ico
│   ├── globals.css
│   ├── layout.tsx
│   └── page.tsx
├── components/
│   ├── enterprise/
│   │   ├── AuditBanner.tsx
│   │   ├── CitationCard.tsx
│   │   ├── ClassificationBadge.tsx
│   │   ├── EmptyState.tsx
│   │   ├── index.ts
│   │   └── PermissionDenied.tsx
│   └── ui/
│       ├── accordion.tsx
│       ├── alert-dialog.tsx
│       ├── avatar.tsx
│       ├── badge.tsx
│       ├── button.tsx
│       ├── card.tsx
│       ├── checkbox.tsx
│       ├── collapsible.tsx
│       ├── combobox.tsx
│       ├── command.tsx
│       ├── context-menu.tsx
│       ├── dialog.tsx
│       ├── dropdown-menu.tsx
│       ├── field.tsx
│       ├── form.tsx
│       ├── input-group.tsx
│       ├── input.tsx
│       ├── label.tsx
│       ├── otp-input.tsx
│       ├── popover.tsx
│       ├── radio-group.tsx
│       ├── scroll-area.tsx
│       ├── select.tsx
│       ├── separator.tsx
│       ├── sheet.tsx
│       ├── sidebar.tsx
│       ├── sonner.tsx
│       ├── switch.tsx
│       ├── table.tsx
│       ├── tabs.tsx
│       ├── textarea.tsx
│       └── tooltip.tsx
├── docs/
│   └── FEATURES_DOCUMENTATION.md
├── lib/
│   └── utils.ts
├── public/
│   ├── assets/
│   │   └── img_pc_zalo.png
│   ├── musics/
│   │   ├── ring.mp3
│   │   └── stop.mp3
│   ├── file.svg
│   ├── globe.svg
│   ├── next.svg
│   ├── vercel.svg
│   └── window.svg
├── src/
│   ├── app/
│   │   └── chat/
│   ├── components/
│   │   ├── group-settings/
│   │   ├── guards/
│   │   └── WorkspaceGuard.tsx
│   ├── features/
│   │   ├── account/
│   │   ├── admin/
│   │   ├── ai/
│   │   ├── auth/
│   │   ├── chat/
│   │   ├── dashboard/
│   │   ├── forgot-password/
│   │   ├── knowledge/
│   │   ├── navigation/
│   │   ├── security/
│   │   ├── settings/
│   │   ├── sign-in/
│   │   ├── sign-up/
│   │   └── verify-email/
│   ├── hooks/
│   │   ├── index.ts
│   │   ├── use-mobile.tsx
│   │   ├── useAIAssistant.ts
│   │   ├── useAIStream.ts
│   │   ├── useRealtimeChat.ts
│   │   └── useRingTone.ts
│   ├── lib/
│   │   ├── rbac/
│   │   ├── socket.ts
│   │   └── textarea-caret.ts
│   ├── redux/
│   │   ├── api/
│   │   ├── feature/
│   │   ├── hooks.ts
│   │   └── store.ts
│   ├── services/
│   │   └── socket.service.ts
│   ├── type/
│   │   ├── auth.types.ts
│   │   └── chat.types.ts
│   ├── utils/
│   │   ├── auth-utils.ts
│   │   └── image-utils.ts
│   └── proxy.ts
├── .gitignore
├── components.json
├── eslint.config.mjs
├── next-env.d.ts
├── next.config.ts
├── package-lock.json
├── package.json
├── postcss.config.mjs
├── README.md
└── tsconfig.json
```

## FE_Moblie (Mobile Frontend)
```
FE_Moblie (Mobile Frontend)/
├── .vscode/
│   ├── extensions.json
│   └── settings.json
├── android/
│   ├── .gradle/
│   │   ├── 8.14.3/
│   │   ├── buildOutputCleanup/
│   │   ├── noVersion/
│   │   └── vcs-1/
│   ├── .kotlin/
│   │   └── sessions/
│   ├── app/
│   │   ├── .cxx/
│   │   ├── src/
│   │   ├── build.gradle
│   │   ├── debug.keystore
│   │   └── proguard-rules.pro
│   ├── gradle/
│   │   └── wrapper/
│   ├── .gitignore
│   ├── build.gradle
│   ├── gradle.properties
│   ├── gradlew
│   ├── gradlew.bat
│   └── settings.gradle
├── app/
│   ├── (auth)/
│   │   ├── _layout.tsx
│   │   ├── forgot-password.tsx
│   │   ├── login.tsx
│   │   ├── register.tsx
│   │   ├── verify-mfa.tsx
│   │   └── verify-otp.tsx
│   ├── (main)/
│   │   ├── admin/
│   │   ├── ai/
│   │   ├── bookmarks/
│   │   ├── channel/
│   │   ├── chat/
│   │   ├── contacts/
│   │   ├── dashboard/
│   │   ├── files/
│   │   ├── knowledge/
│   │   ├── notifications/
│   │   ├── tasks/
│   │   ├── workspace/
│   │   ├── _layout.tsx
│   │   ├── contacts.tsx
│   │   ├── department.tsx
│   │   ├── index.tsx
│   │   ├── media-viewer.tsx
│   │   ├── profile.tsx
│   │   ├── search.tsx
│   │   ├── settings-active-sessions.tsx
│   │   ├── settings-blocked.tsx
│   │   ├── settings-language.tsx
│   │   ├── settings-mfa-setup.tsx
│   │   ├── settings-notifications.tsx
│   │   └── settings.tsx
│   ├── (tabs)/
│   │   ├── _layout.tsx
│   │   ├── explore.tsx
│   │   └── index.tsx
│   ├── _layout.tsx
│   ├── in-call.tsx
│   ├── polyfills.ts
│   └── ringing.tsx
├── assets/
│   └── images/
│       ├── android-icon-background.png
│       ├── android-icon-foreground.png
│       ├── android-icon-monochrome.png
│       ├── favicon.png
│       ├── icon.png
│       ├── partial-react-logo.png
│       ├── react-logo.png
│       ├── react-logo@2x.png
│       ├── react-logo@3x.png
│       └── splash-icon.png
├── components/
│   ├── ui/
│   │   ├── collapsible.tsx
│   │   ├── icon-symbol.ios.tsx
│   │   └── icon-symbol.tsx
│   ├── external-link.tsx
│   ├── haptic-tab.tsx
│   ├── hello-wave.tsx
│   ├── parallax-scroll-view.tsx
│   ├── themed-text.tsx
│   └── themed-view.tsx
├── constants/
│   └── theme.ts
├── hooks/
│   ├── use-color-scheme.ts
│   ├── use-color-scheme.web.ts
│   └── use-theme-color.ts
├── scripts/
│   └── reset-project.js
├── src/
│   ├── components/
│   │   ├── chat/
│   │   ├── contacts/
│   │   ├── guards/
│   │   ├── ui/
│   │   ├── workspace/
│   │   ├── Button.tsx
│   │   ├── ChatListItem.tsx
│   │   ├── DrawerMenu.tsx
│   │   ├── GlobalSocketHandler.tsx
│   │   ├── Input.tsx
│   │   └── SearchBar.tsx
│   ├── features/
│   │   ├── auth/
│   │   └── chat/
│   ├── hooks/
│   │   ├── useConnectionQuality.ts
│   │   ├── useIsSpeaking.ts
│   │   ├── usePushNotifications.ts
│   │   └── useRealtimeChat.ts
│   ├── lib/
│   │   └── rbac/
│   ├── mocks/
│   │   └── livekit-mock.js
│   ├── redux/
│   │   ├── api/
│   │   ├── feature/
│   │   ├── hooks.ts
│   │   └── store.ts
│   ├── services/
│   │   └── socket.service.ts
│   ├── types/
│   │   ├── auth.types.ts
│   │   ├── chat.types.ts
│   │   ├── contact.types.ts
│   │   ├── knowledge.types.ts
│   │   ├── notification.types.ts
│   │   ├── task.types.ts
│   │   └── workspace.types.ts
│   └── utils/
│       ├── device.ts
│       ├── image-utils.ts
│       └── messagePreview.ts
├── .env
├── .gitignore
├── .npmrc
├── app.json
├── eas.json
├── eslint.config.js
├── expo-env.d.ts
├── metro.config.js
├── MOBILE_FLOW_TASKS.md
├── MOBILE_PARITY_TASKS.md
├── package-lock.json
├── package.json
├── README.md
└── tsconfig.json
```

