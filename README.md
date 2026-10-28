# J.A.R.V.I.S. Android — estrutura corrigida para compilação

Esta versão usa um único módulo Android na raiz do repositório. Isso corresponde ao comando `gradle --no-daemon assembleDebug` usado no GitHub Actions e garante que o manifesto esteja no caminho esperado `src/main/AndroidManifest.xml`.

## Publicar
1. Substitua os arquivos do repositório pelos arquivos desta pasta (não envie a pasta externa como uma subpasta do repositório).
2. Faça commit/push para `main` ou `master`, ou execute manualmente a ação `Build JARVIS Android APK`.
3. No GitHub, abra Actions, selecione a execução concluída e baixe o artefato `JARVIS-APK`.

O APK será produzido em `build/outputs/apk/debug/app-debug.apk`.
