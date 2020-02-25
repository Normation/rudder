@format=0
enum generations {
    ascendant,
    descendant,
    sibbling
}
enum generations ~> familytree {
    ascendant -> dad,
    descendant -> son,
    sibbling -> sister
}
