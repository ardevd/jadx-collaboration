package uk.oshawk.jadx.collaboration

import jadx.api.plugins.options.OptionFlag
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder

class Options : BasePluginOptionsBuilder() {
    var repository = ""
    var username = ""

    override fun registerOptions() {
        strOption("${Plugin.ID}.repository")
            .description("Path to the repository file.")
            .defaultValue("")
            .setter { v -> repository = v }
            .flags(OptionFlag.PER_PROJECT, OptionFlag.NOT_CHANGING_CODE)

        strOption("${Plugin.ID}.username")
            .description("Username for collaboration.")
            .defaultValue("")
            .setter { v -> username = v }
            .flags(OptionFlag.PER_PROJECT, OptionFlag.NOT_CHANGING_CODE)
    }
}
