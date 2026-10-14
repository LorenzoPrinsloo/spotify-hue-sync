package domain.engine

import domain.spotify.PlaybackState

import java.time.Instant

case class LocalPlayback(state: PlaybackState, receivedAt: Instant)

