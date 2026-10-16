package domain.hue

case class HueCredentials(
  address: String,
  username: String,
  psk: Psk
)
