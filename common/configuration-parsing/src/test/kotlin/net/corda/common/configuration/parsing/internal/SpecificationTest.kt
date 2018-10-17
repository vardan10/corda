package net.corda.common.configuration.parsing.internal

import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import net.corda.common.validation.internal.Validated
import net.corda.common.validation.internal.Validated.Companion.invalid
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SpecificationTest {

    private object RpcSettingsSpec : Configuration.Specification<RpcSettings>("RpcSettings") {

        private object AddressesSpec : Configuration.Specification<RpcSettings.Addresses>("Addresses") {

            val principal by string().map { key, typeName, rawValue -> NetworkHostAndPort.validFromRawValue(rawValue) { error -> Configuration.Validation.Error.BadValue.of(key, typeName, error) as Configuration.Validation.Error } }

            val admin by string().map { key, typeName, rawValue -> NetworkHostAndPort.validFromRawValue(rawValue) { error -> Configuration.Validation.Error.BadValue.of(key, typeName, error) as Configuration.Validation.Error } }

            override fun parseValid(configuration: Config) = RpcSettings.Addresses(principal.valueIn(configuration), admin.valueIn(configuration))

            @Suppress("UNUSED_PARAMETER")
            fun parse(key: String, typeName: String, rawValue: ConfigObject): Validated<RpcSettings.Addresses, Configuration.Validation.Error> = parse(rawValue.toConfig(), Configuration.Validation.Options(strict = false))
        }

        val useSsl by boolean()
        val addresses by nestedObject(AddressesSpec).map(AddressesSpec::parse)

        override fun parseValid(configuration: Config) = RpcSettingsImpl(addresses.valueIn(configuration), useSsl.valueIn(configuration))
    }

    @Test
    fun parse() {

        val useSslValue = true
        val principalAddressValue = NetworkHostAndPort("localhost", 8080)
        val adminAddressValue = NetworkHostAndPort("127.0.0.1", 8081)
        val addressesValue = configObject("principal" to "${principalAddressValue.host}:${principalAddressValue.port}", "admin" to "${adminAddressValue.host}:${adminAddressValue.port}")
        val configuration = configObject("useSsl" to useSslValue, "addresses" to addressesValue).toConfig()

        val rpcSettings = RpcSettingsSpec.parse(configuration, Configuration.Validation.Options(strict = false))

        assertThat(rpcSettings.isValid).isTrue()
        assertThat(rpcSettings.valueOrThrow()).satisfies { value ->

            assertThat(value.useSsl).isEqualTo(useSslValue)
            assertThat(value.addresses).satisfies { addresses ->

                assertThat(addresses.principal).isEqualTo(principalAddressValue)
                assertThat(addresses.admin).isEqualTo(adminAddressValue)
            }
        }
    }

    @Test
    fun validate() {

        val principalAddressValue = NetworkHostAndPort("localhost", 8080)
        val adminAddressValue = NetworkHostAndPort("127.0.0.1", 8081)
        val addressesValue = configObject("principal" to "${principalAddressValue.host}:${principalAddressValue.port}", "admin" to "${adminAddressValue.host}:${adminAddressValue.port}")
        // Here "useSsl" shouldn't be `null`, hence causing the validation to fail.
        val configuration = configObject("useSsl" to null, "addresses" to addressesValue).toConfig()

        val rpcSettings = RpcSettingsSpec.parse(configuration, Configuration.Validation.Options(strict = false))

        assertThat(rpcSettings.errors).hasSize(1)
        assertThat(rpcSettings.errors.first()).isInstanceOfSatisfying(Configuration.Validation.Error.MissingValue::class.java) { error ->

            assertThat(error.path).containsExactly("useSsl")
        }
    }

    @Test
    fun validate_with_domain_specific_errors() {

        val useSslValue = true
        val principalAddressValue = NetworkHostAndPort("localhost", 8080)
        val adminAddressValue = NetworkHostAndPort("127.0.0.1", 8081)
        // Here, for the "principal" property, the value is incorrect, as the port value is unacceptable.
        val addressesValue = configObject("principal" to "${principalAddressValue.host}:-10", "admin" to "${adminAddressValue.host}:${adminAddressValue.port}")
        val configuration = configObject("useSsl" to useSslValue, "addresses" to addressesValue).toConfig()

        val rpcSettings = RpcSettingsSpec.parse(configuration, Configuration.Validation.Options(strict = false))

        assertThat(rpcSettings.errors).hasSize(1)
        assertThat(rpcSettings.errors.first()).isInstanceOfSatisfying(Configuration.Validation.Error.BadValue::class.java) { error ->

            assertThat(error.path).containsExactly("addresses", "principal")
            assertThat(error.keyName).isEqualTo("principal")
            assertThat(error.typeName).isEqualTo(NetworkHostAndPort::class.java.simpleName)
        }
    }

    @Test
    fun chained_delegated_properties_are_not_added_multiple_times() {

        val spec = object : Configuration.Specification<List<String>?>("Test") {

            @Suppress("unused")
            val myProp by string().list().optional()

            override fun parseValid(configuration: Config) = myProp.valueIn(configuration)
        }

        assertThat(spec.properties).hasSize(1)
    }

    private interface RpcSettings {

        val addresses: Addresses
        val useSsl: Boolean

        data class Addresses(val principal: NetworkHostAndPort, val admin: NetworkHostAndPort)
    }

    private data class RpcSettingsImpl(override val addresses: RpcSettings.Addresses, override val useSsl: Boolean) : RpcSettings

    private data class NetworkHostAndPort(val host: String, val port: Int) {

        init {
            require(host.isNotBlank())
            require(port > 0)
        }

        companion object {

            fun <ERROR> validFromRawValue(rawValue: String, mapError: (String) -> ERROR): Validated<NetworkHostAndPort, ERROR> {

                val parts = rawValue.split(":")
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank() || parts[1].toIntOrNull() == null) {
                    return invalid(sequenceOf("Value format is \"<host(String)>:<port:(Int)>\"").map(mapError).toSet())
                }
                val host = parts[0]
                val port = parts[1].toInt()
                if (port <= 0) {
                    return invalid(sequenceOf("Port value must be greater than zero").map(mapError).toSet())
                }

                return Validated.valid(NetworkHostAndPort(host, port))
            }
        }
    }
}