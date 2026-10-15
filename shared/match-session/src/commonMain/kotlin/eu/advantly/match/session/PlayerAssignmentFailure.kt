package eu.advantly.match.session

/** Ursache einer abgelehnten Personenwahl; keine lokalisierte UI an dieser Grenze. */
enum class PlayerAssignmentFailure { MATCH_UNAVAILABLE, NOT_OWNER, INVALID_SLOT, INVALID_PLAYER, OCCUPIED }
