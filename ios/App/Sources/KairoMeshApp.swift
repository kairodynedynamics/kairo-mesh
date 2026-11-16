import SwiftUI

@main
struct KairoMeshApp: App {
    @StateObject private var mesh = Mesh()
    var body: some Scene { WindowGroup { ContentView().environmentObject(mesh) } }
}

/// The emergency call needs no typing: tap the situation, optionally the number of people and who is with you.
/// Below it: the authority's announcements, settings and the plain chat.
struct ContentView: View {
    @EnvironmentObject private var mesh: Mesh
    @State private var tur: String?
    @State private var kisi = ""
    @State private var ozel = Set<String>()
    @State private var metin = ""
    @State private var kurumKodu = ""
    @State private var name = ""
    @State private var pairing = ""
    @State private var to = ""
    @State private var message = ""
    private let kirmizi = Color(red: 179 / 255, green: 38 / 255, blue: 30 / 255)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Kairo Mesh").font(.largeTitle.bold())
                acilBolumu
                Text("Kurum duyuruları").font(.title3.bold()).padding(.top, 8)
                if mesh.duyurular.isEmpty { Text("Henüz duyuru yok.").foregroundColor(.secondary) }
                ForEach(mesh.duyurular, id: \.self) { Text($0) }

                Text("Ayarlar").font(.title3.bold()).padding(.top, 8)
                TextField("Kurum kodu", text: $kurumKodu).accessibilityIdentifier("kurum")
                Button("Kurum kodunu kaydet") { mesh.setKurum(kurumKodu) }.accessibilityIdentifier("kurumKaydet")
                Text("Eşleşme kodunuz (kopyalamak için dokunun):").font(.subheadline)
                Text(mesh.code).font(.footnote.monospaced()).textSelection(.enabled)
                    .accessibilityIdentifier("code")
                    .onTapGesture { UIPasteboard.general.string = mesh.code }
                TextField("Kişi adı", text: $name).accessibilityIdentifier("name")
                TextField("Kişinin eşleşme kodu", text: $pairing).accessibilityIdentifier("pairing")
                Button("Kişi ekle") { mesh.addContact(name.trimmingCharacters(in: .whitespaces), pairing.trimmingCharacters(in: .whitespaces)) }
                    .accessibilityIdentifier("addContact")
                Text("Mesajlaşma").font(.title3.bold()).padding(.top, 8)
                TextField("Alıcı (kişi adı)", text: $to).accessibilityIdentifier("to")
                TextField("Mesaj", text: $message).accessibilityIdentifier("message")
                Button("Gönder") { mesh.send(to.trimmingCharacters(in: .whitespaces), message); message = "" }
                    .accessibilityIdentifier("send")
                Text(mesh.status).font(.caption.monospaced())
                Text(mesh.log.joined(separator: "\n")).font(.caption.monospaced())
                    .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("log")
            }
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .padding()
        }
    }

    private var acilBolumu: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ACİL YARDIM ÇAĞRISI").font(.title2.bold()).foregroundColor(kirmizi)
            Text(mesh.kurumVar ? "İnternet olmasa da çalışır: çağrınız yakındaki telefonlar üzerinden kuruma taşınır. Konumunuz eklenir."
                               : "Çağrının gideceği kurum tanımlı değil. Kurumdan aldığınız kodu Ayarlar'a girin.")
                .font(.subheadline)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(Cagri.turler, id: \.0) { item in
                    Button { tur = item.0 } label: {
                        Text(item.1).frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .tint(tur == item.0 ? kirmizi : .gray)
                    .accessibilityIdentifier("tur_\(item.0)")
                }
            }
            TextField("Kaç kişisiniz? (isteğe bağlı)", text: $kisi).keyboardType(.numberPad).accessibilityIdentifier("kisi")
            Text("Yanınızda:").font(.subheadline)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]) {
                ForEach(Cagri.ozel, id: \.0) { item in
                    Toggle(item.1, isOn: Binding(get: { ozel.contains(item.0) },
                                                 set: { if $0 { ozel.insert(item.0) } else { ozel.remove(item.0) } }))
                        .toggleStyle(.button)
                }
            }
            TextField("Ya da yazın: adres, durum (ör. antakya ürgenpaşa mh enkazdayız 3 kişi)", text: $metin, axis: .vertical)
                .accessibilityIdentifier("metin")
            Button {
                // a situation or some text is enough: the phone reads the text when nothing was tapped
                mesh.emergency(tur: tur, kisi: Int(kisi.trimmingCharacters(in: .whitespaces)), ozel: ozel, metin: metin)
            } label: {
                Text("ÇAĞRIYI GÖNDER").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent).tint(kirmizi)
            .disabled(tur == nil && metin.trimmingCharacters(in: .whitespaces).isEmpty)
            .accessibilityIdentifier("acilGonder")
            Text(mesh.acilDurum).font(.headline).accessibilityIdentifier("acilDurum")
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(kirmizi.opacity(0.08)))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(kirmizi, lineWidth: 2))
    }
}
