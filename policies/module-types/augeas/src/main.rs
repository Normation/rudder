// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS ;

use anyhow::Result;

// binaire raugtool ("rudder agent augeas" séparé ??
// mode hybride augtool / rudder)
// implémenter une commande pour le diff!
// intercepter le help et le modifier!

// faire juste un reload du tree devrait virer les trucs en cours
// on devrit pas avoir de pb avec les lens.

// très important!
// https://github.com/hercules-team/augeas/issues/68
// https://github.com/dominikh/go-augeas/blob/master/augeas.go

fn main() -> Result<(), anyhow::Error> {
    rudder_module_augeas::entry()
}
