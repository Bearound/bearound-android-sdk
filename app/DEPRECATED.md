# ⚠️ Módulo deprecado

Este módulo (`:app` / `io.bearound.scan`) é o example **legado** e não deve receber
evolução. O example canônico — e a referência de integração de cliente — é o
**`:BearoundScan`** (`io.bearound.bearoundscan`):

- usa `ForegroundScanConfig` (foreground service, a integração recomendada);
- reconfigura via stop+start (aqui, `updateConfiguration()` **não** reinicia o scan —
  mudanças de precision silenciosamente não valem);
- UI honesta de Bluetooth desligado e de estado de zona.

Mantido apenas como referência histórica.
