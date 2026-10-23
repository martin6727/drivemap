# Paho MQTT v5 finds its network modules (tcp/ssl/ws) with ServiceLoader and logs via a
# resource bundle, so keep the client classes intact.
-keep class org.eclipse.paho.mqttv5.** { *; }
-dontwarn org.eclipse.paho.**
-dontwarn javax.naming.**

# MapLibre ships its own consumer rules; keep geojson model classes used across JNI just in case.
-keep class org.maplibre.geojson.** { *; }
