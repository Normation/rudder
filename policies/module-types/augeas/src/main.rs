// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS ;

use anyhow::Result;

// transofrmer un diff en conf augeas??
// outil pour savoir comment écrire le set ??
// ou pour prendre l'état d'un fichier ?

// voir si les span peuvent ider à faire du debug/reporting

// diff de valeur pour les audits TODO

// implémenter une commande pour le diff!
// intercepter le help et le modifier!

// Pourquoi pas du srun : pour pouvoir interrompre au premier problème

// très important!
// https://github.com/hercules-team/augeas/issues/68
// https://github.com/dominikh/go-augeas/blob/master/augeas.go

// diff de valeur d'audit expected vs constraint

// diff de valeur sur les set ??

// observabilité d'augeas !

fn main() -> Result<(), anyhow::Error> {
    rudder_module_augeas::entry()
}
