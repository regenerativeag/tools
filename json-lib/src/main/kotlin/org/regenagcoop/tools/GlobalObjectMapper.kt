package org.regenagcoop.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/** A thread safe, consistently configured object mapper across all subprojects */
val GlobalObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()