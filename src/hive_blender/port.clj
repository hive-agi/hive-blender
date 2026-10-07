(ns hive-blender.port
  "The serialized Blender connection and mandatory code-policy ports.")

(defprotocol BlenderLink
  (send! [this command] "Send one command and return a typed outcome; never retry unknown outcomes.")
  (close! [this] "Discard the connection."))

(defprotocol CodeGate
  (allow-code? [this code] "Return true only for authorized bounded code."))
