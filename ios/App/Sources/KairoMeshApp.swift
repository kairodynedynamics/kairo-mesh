import SwiftUI

@main
struct KairoMeshApp: App {
    @StateObject private var mesh = Mesh()
    var body: some Scene { WindowGroup { ContentView().environmentObject(mesh) } }
}

struct ContentView: View {
    @EnvironmentObject private var mesh: Mesh
    @State private var name = "", pairing = "", to = "", message = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Kairo Mesh").font(.largeTitle.bold())
            Text("Your pairing code (tap to copy):").font(.subheadline)
            Text(mesh.code).font(.footnote.monospaced()).textSelection(.enabled)
                .accessibilityIdentifier("code")
                .onTapGesture { UIPasteboard.general.string = mesh.code }
            TextField("Contact name", text: $name).accessibilityIdentifier("name")
            TextField("Their pairing code", text: $pairing).accessibilityIdentifier("pairing")
            Button("Add contact") { mesh.addContact(name.trimmingCharacters(in: .whitespaces), pairing.trimmingCharacters(in: .whitespaces)) }
            TextField("Send to (contact name)", text: $to).accessibilityIdentifier("to")
            TextField("Message", text: $message).accessibilityIdentifier("message")
            Button("Send") { mesh.send(to.trimmingCharacters(in: .whitespaces), message); message = "" }
            Text(mesh.status).font(.caption.monospaced())
            ScrollView {
                Text(mesh.log.joined(separator: "\n")).font(.caption.monospaced())
                    .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("log")
            }
        }
        .textFieldStyle(.roundedBorder)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .padding()
    }
}
