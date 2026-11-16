import AVFoundation
import SwiftUI
import UIKit

@main
struct KairoMeshApp: App {
    @StateObject private var mesh = Mesh()
    var body: some Scene { WindowGroup { ContentView().environmentObject(mesh) } }
}

/// KairoMesh's one screen, the same as Android's (docs/08-acil-ekran.md): four large life-threatening situations,
/// the selection written out with an undo, typing alone enough, a summary of exactly what will be sent, a 5-second
/// countdown with a large CANCEL, a correction for a call sent by mistake, a sound signal for rescuers.
/// Light grey, slightly glossy theme with near-black text.
struct ContentView: View {
    @EnvironmentObject private var mesh: Mesh
    @State private var tur: String?
    @State private var kisi: Int?
    @State private var ozel = Set<String>()
    @State private var metin = ""
    @State private var geriSayim: Int?
    @State private var duzeltOnay = false
    @State private var guvendeOnay = false
    @State private var ayarlar = false
    @State private var sinyal = false
    @State private var kurumKodu = ""
    @State private var name = ""
    @State private var pairing = ""
    @State private var to = ""
    @State private var message = ""
    private let sayac = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            LinearGradient(colors: [Tema.zeminUst, Tema.zeminAlt], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    baslik
                    acilKart
                    Button { sinyalDegistir() } label: {
                        Text(sinyal ? "■ Sesli sinyali durdur" : "♪ Sesli sinyal (kurtarıcılar sizi duysun)").kalinDugme(Tema.koyu, .white)
                    }.accessibilityIdentifier("sinyal")
                    Button {
                        if guvendeOnay { tur = "guvende"; gonderBas() } else { guvendeOnay = true }
                    } label: {
                        Text(guvendeOnay ? "Emin misiniz? Güvende olduğunuzu bildirmek için tekrar dokunun" : "Güvendeyim — kuruma bildir")
                            .kalinDugme(Tema.yesilZemin, Tema.yesil, kenar: Tema.yesil)
                    }
                    kart {
                        Text("Kurum duyuruları").font(.title3.bold()).foregroundColor(Tema.metin)
                        if mesh.duyurular.isEmpty { Text("Henüz duyuru yok.").foregroundColor(Tema.ikincil) }
                        ForEach(mesh.duyurular, id: \.self) { Text($0).foregroundColor(Tema.metin) }
                    }
                    Button { ayarlar.toggle() } label: { Text("Ayarlar ve mesajlaşma \(ayarlar ? "▴" : "▾")").kalinDugme(Tema.kart, Tema.metin, kenar: Tema.cizgi) }
                        .accessibilityIdentifier("ayarlar")
                    if ayarlar { ayarlarKart }
                    #if DEBUG
                    // the technical log, for development and UI tests only
                    Text(mesh.log.joined(separator: "\n")).font(.caption2.monospaced()).foregroundColor(Tema.ikincil)
                        .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("log")
                    #endif
                }
                .padding(16)
            }
            if let kalan = geriSayim { geriSayimKatmani(kalan) }
        }
        .preferredColorScheme(.light)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true; mesh.konumIste() } // the screen must not go dark under rubble
        .onReceive(sayac) { _ in
            guard let k = geriSayim else { return }
            if k <= 1 { gonder() } else { geriSayim = k - 1; UIAccessibility.post(notification: .announcement, argument: "\(k - 1)") }
        }
    }

    // MARK: parts

    private var baslik: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Circle().fill(Tema.kirmizi).frame(width: 14, height: 14)
                Text("KairoMesh").font(.system(size: 28, weight: .heavy)).foregroundColor(Tema.metin)
            }
            Text(mesh.kurumVar ? "Çağrılarınız şu kuruma gider: \(mesh.kurumAd)" : "Kurum kodu tanımlı değil: aşağıdaki Ayarlar bölümüne kurumun kodunu girin.")
                .font(.subheadline).foregroundColor(Tema.ikincil)
            Text(agYazisi).font(.callout.bold()).foregroundColor(mesh.internet || mesh.yakin > 0 ? Tema.yesil : Tema.amber)
        }
    }

    private var agYazisi: String {
        if mesh.internet { return "● İnternet var · çağrılar doğrudan kuruma gider · yakında \(mesh.yakin) telefon" }
        if mesh.yakin > 0 { return "● İnternet yok · yakında \(mesh.yakin) telefon taşıyabilir" }
        return "● İnternet yok · yakında telefon görünmüyor; çağrınız biri yaklaşınca gider"
    }

    private var acilKart: some View {
        kart {
            Text("ACİL YARDIM ÇAĞRISI").font(.title2.weight(.heavy)).foregroundColor(Tema.kirmizi)
            Text("İnternet olmasa da çalışır. 1) Durumunuza dokunun ya da yazın, 2) Gönder'e basın.").foregroundColor(Tema.ikincil)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(Cagri.hayati, id: \.self) { k in durumDugmesi(k, buyuk: true) }
            }
            Text("Hayati değilse:").font(.subheadline.bold()).foregroundColor(Tema.ikincil)
            HStack(spacing: 6) { ForEach(Cagri.ihtiyac, id: \.self) { k in durumDugmesi(k, buyuk: false) } }
            HStack {
                Text(tur.map { "Seçiminiz: \(Cagri.ad($0))" } ?? "Henüz durum seçmediniz.").font(.headline)
                    .foregroundColor(tur == nil ? Tema.ikincil : Tema.kirmizi).accessibilityIdentifier("secim")
                Spacer()
                if tur != nil {
                    Button("Kaldır") { secimDegistir(nil) }.font(.headline).foregroundColor(Tema.kirmizi)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: 12).stroke(Tema.kirmizi, lineWidth: 2))
                        .accessibilityLabel("Seçimi kaldır")
                }
            }
            Text("Kaç kişisiniz?").font(.headline).foregroundColor(Tema.metin)
            HStack(spacing: 14) {
                Button { kisi = kisi.flatMap { $0 <= 1 ? nil : $0 - 1 } } label: { Text("−").font(.system(size: 30, weight: .bold)).frame(width: 64, height: 54) }
                    .buttonStyle(KutuStil()).accessibilityLabel("Bir azalt")
                Text(kisi.map(String.init) ?? "?").font(.system(size: 30, weight: .heavy)).frame(minWidth: 50).foregroundColor(Tema.metin)
                Button { kisi = min(99, (kisi ?? 0) + 1) } label: { Text("+").font(.system(size: 30, weight: .bold)).frame(width: 64, height: 54) }
                    .buttonStyle(KutuStil()).accessibilityLabel("Bir artır")
                Text("? = bilmiyorum").font(.subheadline).foregroundColor(Tema.ikincil)
            }
            Text("Yanınızda:").font(.headline).foregroundColor(Tema.metin)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 6) {
                ForEach(Cagri.ozel, id: \.0) { item in
                    let secili = ozel.contains(item.0)
                    Button { if secili { ozel.remove(item.0) } else { ozel.insert(item.0) } } label: {
                        Text((secili ? "✓ " : "") + item.1).font(.headline).frame(maxWidth: .infinity, minHeight: 50)
                            .foregroundColor(secili ? .white : Tema.metin)
                            .background(RoundedRectangle(cornerRadius: 24).fill(secili ? Tema.koyu : Tema.kart))
                            .overlay(RoundedRectangle(cornerRadius: 24).stroke(secili ? Tema.koyu : Tema.cizgi, lineWidth: 2))
                    }.accessibilityValue(secili ? "seçili" : "seçili değil")
                }
            }
            Text("Ya da yazın (isteğe bağlı):").font(.headline).foregroundColor(Tema.metin)
            TextField("Adres ve durum, ör. antakya ürgenpaşa mh enkazdayız 3 kişi", text: $metin, axis: .vertical)
                .font(.body).lineLimit(2...5).padding(12).foregroundColor(Tema.metin)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color.white)).overlay(RoundedRectangle(cornerRadius: 12).stroke(Tema.cizgi, lineWidth: 2))
                .accessibilityIdentifier("metin")
            if !metin.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(tur.map { "Seçtiğiniz \"\(Cagri.ad($0))\" de yazdığınızla birlikte gönderilecek. İstemiyorsanız yukarıdan kaldırın." }
                     ?? "Durum seçmediniz: yazdığınızdan durum telefonda anlaşılacak ve çağrı acil olarak gönderilecek.")
                    .font(.subheadline.bold()).foregroundColor(Tema.amber)
            }
            Text(ozetYazisi).foregroundColor(Tema.metin).frame(maxWidth: .infinity, alignment: .leading).padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Tema.ozetZemin)).overlay(RoundedRectangle(cornerRadius: 12).stroke(Tema.cizgi, lineWidth: 1.5))
            Button { gonderBas() } label: { Text("ÇAĞRIYI GÖNDER").font(.system(size: 24, weight: .heavy)).frame(maxWidth: .infinity, minHeight: 64) }
                .foregroundColor(.white).background(RoundedRectangle(cornerRadius: 14).fill(Tema.kirmizi)).accessibilityIdentifier("acilGonder")
            if !mesh.acilDurum.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text(mesh.acilDurum).font(.headline).foregroundColor(Tema.metin).accessibilityIdentifier("acilDurum")
                    if mesh.sonCagriId != nil && !mesh.kurumAldi {
                        Button {
                            // a second tap confirms: correcting a real call by accident would be as bad as a false one
                            if duzeltOnay { mesh.duzelt(); duzeltOnay = false } else { duzeltOnay = true }
                        } label: {
                            Text(duzeltOnay ? "Emin misiniz? Onaylamak için tekrar dokunun" : "Yanlışlıkla gönderdim / artık yardım gerekmiyor")
                                .kalinDugme(Tema.kart, Tema.metin, kenar: Tema.cizgi)
                        }.accessibilityIdentifier("duzelt")
                    }
                }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 14).fill(mesh.kurumAldi ? Tema.yesilZemin : Tema.kirmiziZemin))
            }
        }
    }

    private func durumDugmesi(_ k: String, buyuk: Bool) -> some View {
        let secili = tur == k
        return Button { secimDegistir(k) } label: {
            VStack(alignment: buyuk ? .leading : .center, spacing: 4) {
                Text(Cagri.ad(k)).font(buyuk ? .title3.bold() : .subheadline.bold())
                    .foregroundColor(secili ? .white : (buyuk ? Tema.kirmizi : Tema.metin)).multilineTextAlignment(buyuk ? .leading : .center)
                if buyuk || secili {
                    Text(secili ? "✓ SEÇİLİ — kaldırmak için tekrar dokunun" : (Cagri.altYazi[k] ?? ""))
                        .font(buyuk ? .subheadline : .caption2).foregroundColor(secili ? .white : Tema.ikincil)
                }
            }
            .frame(maxWidth: .infinity, minHeight: buyuk ? 96 : 60, alignment: buyuk ? .topLeading : .center).padding(12)
            .background(RoundedRectangle(cornerRadius: 16).fill(secili ? Tema.kirmizi : Tema.kart))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(secili ? Tema.kirmizi : (buyuk ? Tema.kirmiziKenar : Tema.cizgi), lineWidth: 2))
        }
        .accessibilityIdentifier("tur_\(k)").accessibilityValue(secili ? "seçili" : "seçili değil")
    }

    private var ozetYazisi: String {
        let yazili = !metin.trimmingCharacters(in: .whitespaces).isEmpty
        var s = "Gönderilecek:\n• Durum: \(tur.map(Cagri.ad) ?? (yazili ? "yazdığınızdan anlaşılacak" : "—"))\n"
        s += "• Kişi sayısı: \(kisi.map(String.init) ?? "belirtilmedi")\n"
        s += "• Yanınızda: \(ozel.isEmpty ? "belirtilmedi" : ozel.sorted().map(Cagri.ozelAd).joined(separator: ", "))\n"
        if yazili { s += "• Mesaj: \"\(String(metin.trimmingCharacters(in: .whitespaces).prefix(80)))\"\n" }
        return s + "• Konum: izin verildiyse GPS eklenir"
    }

    private func geriSayimKatmani(_ kalan: Int) -> some View {
        ZStack {
            Color(red: 14 / 255, green: 26 / 255, blue: 34 / 255).opacity(0.97).ignoresSafeArea()
            VStack(spacing: 22) {
                Text("Çağrı \(kalan) saniye içinde gönderilecek").font(.system(size: 28, weight: .heavy)).foregroundColor(.white).multilineTextAlignment(.center)
                Button { geriSayim = nil; mesh.iptalBildir() } label: { Text("İPTAL").font(.system(size: 34, weight: .heavy)).frame(maxWidth: .infinity, minHeight: 96) }
                    .foregroundColor(Tema.kirmizi).background(RoundedRectangle(cornerRadius: 16).fill(Color.white)).accessibilityIdentifier("iptal")
                Button { gonder() } label: { Text("Hemen gönder").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 60) }
                    .foregroundColor(.white).background(RoundedRectangle(cornerRadius: 14).fill(Tema.kirmizi))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white, lineWidth: 2)).accessibilityIdentifier("hemenGonder")
            }.padding(24)
        }
    }

    private var ayarlarKart: some View {
        kart {
            Text("Kurum kodu").font(.headline).foregroundColor(Tema.metin)
            Text("Kurumun resmî kanalından aldığınız kodu yapıştırın. Onaysız kod kabul edilmez.").font(.subheadline).foregroundColor(Tema.ikincil)
            TextField("Kurum kodu", text: $kurumKodu).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never)
                .accessibilityIdentifier("kurum")
            Button("Kurum kodunu kaydet") { mesh.setKurum(kurumKodu) }.accessibilityIdentifier("kurumKaydet")
            Text("Eşleşme kodunuz (kopyalamak için dokunun):").font(.headline).foregroundColor(Tema.metin).padding(.top, 8)
            Text(mesh.code).font(.footnote.monospaced()).foregroundColor(Tema.ikincil).textSelection(.enabled)
                .accessibilityIdentifier("code").onTapGesture { UIPasteboard.general.string = mesh.code }
            TextField("Kişi adı", text: $name).textFieldStyle(.roundedBorder).accessibilityIdentifier("name")
            TextField("Kişinin eşleşme kodu", text: $pairing).textFieldStyle(.roundedBorder).accessibilityIdentifier("pairing")
            Button("Kişi ekle") { mesh.addContact(name.trimmingCharacters(in: .whitespaces), pairing.trimmingCharacters(in: .whitespaces)) }
                .accessibilityIdentifier("addContact")
            Text("Mesajlaşma").font(.headline).foregroundColor(Tema.metin).padding(.top, 8)
            TextField("Alıcı (kişi adı)", text: $to).textFieldStyle(.roundedBorder).accessibilityIdentifier("to")
            TextField("Mesaj", text: $message).textFieldStyle(.roundedBorder).accessibilityIdentifier("message")
            Button("Gönder") { mesh.send(to.trimmingCharacters(in: .whitespaces), message); message = "" }.accessibilityIdentifier("send")
        }
        .autocorrectionDisabled().textInputAutocapitalization(.never)
    }

    private func kart<C: View>(@ViewBuilder _ icerik: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12, content: icerik).padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16).fill(Tema.kart).shadow(color: .black.opacity(0.08), radius: 4, y: 2))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Tema.cizgi, lineWidth: 1.5))
    }

    // MARK: actions

    private func secimDegistir(_ k: String?) {
        // tapping the selected situation again clears it: a mistaken tap is undone where it happened
        tur = (k == nil || tur == k) ? nil : k
        UIAccessibility.post(notification: .announcement, argument: tur.map { "\(Cagri.ad($0)) seçildi" } ?? "Seçim kaldırıldı")
    }

    private func gonderBas() {
        guvendeOnay = false
        guard tur != nil || !metin.trimmingCharacters(in: .whitespaces).isEmpty else { return mesh.durumYaz("Önce durumunuzu seçin ya da kısaca yazın.") }
        guard mesh.kurumVar else { return mesh.kurumYok() }
        mesh.konumIste()
        if tur == "guvende" { return gonder() } // "I am safe" has no countdown: nothing urgent can go out by mistake
        geriSayim = 5 // a stray tap must not send a call: five seconds with a large CANCEL
    }

    private func gonder() {
        geriSayim = nil
        mesh.emergency(tur: tur, kisi: kisi, ozel: ozel, metin: metin)
    }

    private func sinyalDegistir() {
        sinyal.toggle()
        if sinyal { Siren.shared.baslat() } else { Siren.shared.durdur() }
    }
}

/// A loud repeating tone so rescuers can find a person they cannot see. Plays through the speaker even when the
/// ringer is silent.
final class Siren {
    static let shared = Siren()
    private var oynatici: AVAudioPlayer?

    func baslat() {
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        oynatici = try? AVAudioPlayer(data: Siren.ton())
        oynatici?.numberOfLoops = -1
        oynatici?.volume = 1
        oynatici?.play()
    }

    func durdur() { oynatici?.stop(); oynatici = nil }

    /// 0.7 s of 3.1 kHz (a whistle's pitch, carries well through debris) then 0.9 s silence, as 16-bit mono WAV.
    private static func ton() -> Data {
        let hz = 22050, n = Int(Double(hz) * 1.6)
        var ornek = [Int16](repeating: 0, count: n)
        for i in 0..<Int(Double(hz) * 0.7) { ornek[i] = Int16(sin(2 * Double.pi * 3100 * Double(i) / Double(hz)) * 30000) }
        var d = Data()
        func ekle<T>(_ v: T) { withUnsafeBytes(of: v) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); ekle(UInt32(36 + n * 2).littleEndian); d.append(contentsOf: Array("WAVEfmt ".utf8))
        ekle(UInt32(16).littleEndian); ekle(UInt16(1).littleEndian); ekle(UInt16(1).littleEndian); ekle(UInt32(hz).littleEndian)
        ekle(UInt32(hz * 2).littleEndian); ekle(UInt16(2).littleEndian); ekle(UInt16(16).littleEndian)
        d.append(contentsOf: Array("data".utf8)); ekle(UInt32(n * 2).littleEndian)
        for o in ornek { ekle(o.littleEndian) }
        return d
    }
}

/// The light grey, slightly glossy theme (same values as Android's MainActivity).
enum Tema {
    static let zeminUst = Color(red: 236 / 255, green: 239 / 255, blue: 242 / 255)
    static let zeminAlt = Color(red: 214 / 255, green: 220 / 255, blue: 225 / 255)
    static let kart = Color(red: 248 / 255, green: 249 / 255, blue: 250 / 255)
    static let cizgi = Color(red: 192 / 255, green: 200 / 255, blue: 207 / 255)
    static let ozetZemin = Color(red: 236 / 255, green: 240 / 255, blue: 243 / 255)
    static let metin = Color(red: 14 / 255, green: 26 / 255, blue: 34 / 255)
    static let ikincil = Color(red: 58 / 255, green: 72 / 255, blue: 83 / 255)
    static let koyu = Color(red: 38 / 255, green: 52 / 255, blue: 62 / 255)
    static let kirmizi = Color(red: 179 / 255, green: 38 / 255, blue: 30 / 255)
    static let kirmiziKenar = Color(red: 214 / 255, green: 140 / 255, blue: 132 / 255)
    static let kirmiziZemin = Color(red: 251 / 255, green: 228 / 255, blue: 225 / 255)
    static let yesil = Color(red: 11 / 255, green: 110 / 255, blue: 79 / 255)
    static let yesilZemin = Color(red: 221 / 255, green: 240 / 255, blue: 231 / 255)
    static let amber = Color(red: 138 / 255, green: 90 / 255, blue: 0)
}

private struct KutuStil: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundColor(Tema.metin)
            .background(RoundedRectangle(cornerRadius: 14).fill(configuration.isPressed ? Tema.ozetZemin : Tema.kart))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Tema.cizgi, lineWidth: 2))
    }
}

private extension Text {
    func kalinDugme(_ zemin: Color, _ yazi: Color, kenar: Color? = nil) -> some View {
        self.font(.headline).foregroundColor(yazi).multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 56).padding(.horizontal, 12)
            .background(RoundedRectangle(cornerRadius: 14).fill(zemin)).overlay(RoundedRectangle(cornerRadius: 14).stroke(kenar ?? zemin, lineWidth: 2))
    }
}
