package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon

fun interface ForceField {
    suspend fun influence(photon: Photon): FieldInfluence?
}
