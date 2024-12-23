// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS ;

use anyhow::Result;

// raugeas = augeas + audit

// transofrmer un diff en conf augeas??

// vraie attention sur les perfs

// Pas de modif des lens au runtime, il faut les placer dan sle rpeo magisue de l'agent.

// https://github.com/georgehansper/augprint.py
// outil pour savoir comment écrire le set ??
// ou pour prendre l'état d'un fichier ?

// voir si les span peuvent ider à faire du debug/reporting

// diff de valeur pour les audits TODO
// rendre augeas bavard !

// binaire raugtool ("rudder agent augeas" séparé ??
// mode hybride augtool / rudder)
// implémenter une commande pour le diff!
// intercepter le help et le modifier!

// change language?? is it really useful?
// => oui, pur la gestion d'erreur plus fine et pouvoir dire
// où ça a cassé, et autant que possible pourquoi.

// faire juste un reload du tree devrait virer les trucs en cours
// on devrit pas avoir de pb avec les lens.

// très important!
// https://github.com/hercules-team/augeas/issues/68
// https://github.com/dominikh/go-augeas/blob/master/augeas.go

fn main() -> Result<(), anyhow::Error> {
    rudder_module_augeas::entry()
}
