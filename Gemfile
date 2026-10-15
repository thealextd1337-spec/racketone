source "https://rubygems.org"

# Versionsquelle bleibt der zentrale Gradle-Katalog.
versions = File.read(File.join(__dir__, "gradle/libs.versions.toml"))
fastlane_version = versions.match(/^fastlane = "([^"]+)"$/)[1]
gem "fastlane", fastlane_version
