package au.com.dius.pact.provider.gradle

import au.com.dius.pact.model.Pact
import au.com.dius.pact.model.Pact$
import au.com.dius.pact.model.Interaction
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.json4s.FileInput
import org.json4s.StreamInput
import scala.collection.JavaConverters$
import groovyx.net.http.RESTClient

class PactVerificationTask extends DefaultTask {

    ProviderInfo providerToVerify

    @TaskAction
    void verifyPact() {
        ext.failures = [:]
        providerToVerify.consumers.each { consumer ->
            Pact pact
            if (consumer.pactFile instanceof File) {
                pact = Pact$.MODULE$.from(new FileInput(consumer.pactFile))
            } else if (consumer.pactFile instanceof URL) {
                pact = Pact$.MODULE$.from(new StreamInput(consumer.pactFile.newInputStream()))
            } else {
                throw new RuntimeException('You must specify the pactfile to execute (use pactFile = ...)')
            }

            AnsiConsole.out().println(Ansi.ansi().a('\nVerifying a pact between ').bold().a(consumer.name)
                .boldOff().a(' and ').bold().a(providerToVerify.name).boldOff())
            def interactions = JavaConverters$.MODULE$.asJavaIteratorConverter(pact.interactions().iterator())
            interactions.asJava().each { Interaction interaction ->
                def interactionMessage = "Verifying a pact between ${consumer.name} and ${providerToVerify.name} - ${interaction.description()}"

                def stateChangeOk = true
                if (interaction.providerState.defined) {
                    stateChangeOk = stateChange(interaction.providerState.get(), consumer)
                    if (stateChangeOk != true) {
                        ext.failures[interactionMessage] = stateChangeOk
                        stateChangeOk = false
                    } else {
                        interactionMessage += " Given " + interaction.providerState.get()
                    }
                }

                if (stateChangeOk) {
                    AnsiConsole.out().println(Ansi.ansi().a('  ').a(interaction.description()))

                    try {
                        ProviderClient client = new ProviderClient(request: interaction.request(), provider: providerToVerify)

                        def expectedResponse = interaction.response()
                        def actualResponse = client.makeRequest()
                        def comparison = ResponseComparison.compareResponse(expectedResponse, actualResponse)

                        AnsiConsole.out().println('    returns a response which')
                        displayMethodResult(failures, expectedResponse.status(), comparison.method,
                            interactionMessage + ' returns a response which')
                        displayHeadersResult(failures, expectedResponse.headers(), comparison.headers,
                            interactionMessage + ' returns a response which')
                        def expectedBody = expectedResponse.body().defined ? expectedResponse.bodyString().get() : ''
                        displayBodyResult(failures, expectedBody, comparison.body,
                            interactionMessage + ' returns a response which')
                    } catch (e) {
                        AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a('Request Failed - ')
                            .a(e.message).reset())
                        ext.failures[interactionMessage] = e
                        if (project.hasProperty('pact.showStacktrace')) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }


        if (ext.failures.size() > 0) {
            AnsiConsole.out().println('\nFailures:\n')
            failures.eachWithIndex { err, i ->
                AnsiConsole.out().println("$i) ${err.key}")
                if (err.value instanceof Exception) {
                    err.value.message.split('\n').each {
                        AnsiConsole.out().println("      $it")
                    }
                } else if (err.value instanceof Map && err.value.containsKey('diff')) {
                    err.value.comparison.each { key, message ->
                        AnsiConsole.out().println("      $key -> $message")
                    }

                    AnsiConsole.out().println()
                    AnsiConsole.out().println("      Diff:")
                    AnsiConsole.out().println()

                    err.value.diff.each { delta ->
                        if (delta.startsWith('@')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.CYAN).a(delta).reset())
                        } else if (delta.startsWith('-')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a(delta).reset())
                        } else if (delta.startsWith('+')) {
                            AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.GREEN).a(delta).reset())
                        } else {
                            AnsiConsole.out().println("      $delta")
                        }
                    }
                } else {
                    err.value.each { key, message ->
                        AnsiConsole.out().println("      $key -> $message")
                    }
                }
                AnsiConsole.out().println()
            }

            throw new RuntimeException("There were ${failures.size()} pact failures for provider ${providerToVerify.name}")
        }
    }

    void displayMethodResult(Map failures, int status, def comparison, String comparisonDescription) {
        def ansi = Ansi.ansi().a('      ').a('has status code ').bold().a(status).boldOff().a(' (')
        if (comparison == true) {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
        } else {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
            failures["$comparisonDescription has status code $status"] = comparison
        }
    }

    void displayHeadersResult(Map failures, def expected, Map comparison, String comparisonDescription) {
        if (!comparison.isEmpty()) {
            AnsiConsole.out().println('      includes headers')
            Map expectedHeaders = JavaConverters$.MODULE$.asJavaMapConverter(expected.get()).asJava()
            comparison.each { key, headerComparison ->
                def expectedHeaderValue = expectedHeaders[key]
                def ansi = Ansi.ansi().a('        "').bold().a(key).boldOff().a('" with value "').bold()
                    .a(expectedHeaderValue).boldOff().a('" (')
                if (headerComparison == true) {
                    AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
                } else {
                    AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
                    failures["$comparisonDescription includes headers \"$key\" with value \"$expectedHeaderValue\""] = headerComparison
                }
            }
        }
    }

    void displayBodyResult(Map failures, String body, def comparison, String comparisonDescription) {
        def ansi = Ansi.ansi().a('      ').a('has a matching body').a(' (')
        if (comparison.isEmpty()) {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
        } else {
            AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
            failures["$comparisonDescription has a matching body"] = comparison
        }
    }

    def stateChange(String state, ConsumerInfo consumer) {
        AnsiConsole.out().println(Ansi.ansi().a('  Given ').bold().a(state).boldOff())
        try {
            def client = new RESTClient(consumer.stateChange.toString())
            if (consumer.stateChangeUsesBody) {
                client.post(body: [state: state], requestContentType: 'application/json')
            } else {
                client.post(query: [state: state])
            }
            return true
        } catch (e) {
            AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.RED).a('State Change Request Failed - ')
                .a(e.message).reset())
            if (project.hasProperty('pact.showStacktrace')) {
                e.printStackTrace()
            }
            return e
        }
    }
}
