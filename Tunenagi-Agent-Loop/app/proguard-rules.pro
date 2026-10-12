# Tunenagi app proguard baseline.
#
# Minification is disabled in the dev build so these rules are defensive. They exist
# so a release build path is owned from the start. Update them when the release path
# is enabled; do not ship with an untested minified build.
#
# -keep rules for serialization / Hilt / Room would go here once those features are
# wired for release. For now the app is small enough that the default Android rules
# plus this marker are the right starting contract.
