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
/// Inset-grouped white cards on a grey ground, numbered steps, one accent: red.
struct ContentView: View {
    @EnvironmentObject private var mesh: Mesh
    @State private var tur: String?
    @State private var kisi: Int?
    @State private var ozel = Set<String>()
    @State private var metin = ""
    @State private var geriSayim: Int?
    @State private var sinyal = false
    @State private var arkaPlan: Date?
    @FocusState private var yaziyor: Bool
    @Environment(\.scenePhase) private var faz
    @Environment(\.accessibilityReduceMotion) private var azHareket
    private let sayac = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var yay: Animation? { azHareket ? nil : .spring(response: 0.32, dampingFraction: 0.72) }

    var body: some View {
        ZStack {
            LinearGradient(colors: [Tema.zeminUst, Tema.zeminAlt], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    baslik
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Afet yardım çağrısı").font(.system(size: 26, weight: .bold)).foregroundColor(Tema.metin)
                            .accessibilityAddTraits(.isHeader)
                        Text("Doğal afette şebeke çöktüğünde kullanın; günlük acil durumlarda 112'yi arayın. Durumunuzu seçin ya da yazın, sonra gönderin.")
                            .font(.callout).foregroundColor(Tema.ikincil)
                    }.padding(.top, 14)
                    neOldu
                    kacKisi
                    yazKart
                    gonderKart
                    Button { sinyalDegistir() } label: {
                        Text(sinyal ? "■ Sesli sinyali durdur" : "♪ Sesli sinyal · kurtarıcılar sizi duysun")
                            .kalinDugme(Tema.kart, Tema.metin, boy: 60)
                    }.buttonStyle(BasincStil()).accessibilityIdentifier("sinyal").padding(.top, 4)
                    Button {
                        tur = "guvende"; gonderBas() // one tap: "I am safe" never sends anything urgent
                    } label: {
                        Text("✓ Güvendeyim · kuruma bildir").kalinDugme(Tema.yesilZemin, Tema.yesil, boy: 60)
                    }.buttonStyle(BasincStil())
                    kart {
                        Text("Kurum duyuruları").font(.system(size: 19, weight: .semibold)).foregroundColor(Tema.metin)
                            .accessibilityAddTraits(.isHeader)
                        if mesh.duyurular.isEmpty { Text("Henüz duyuru yok.").foregroundColor(Tema.ikincil) }
                        ForEach(mesh.duyurular, id: \.self) { Text($0).foregroundColor(Tema.metin) }
                    }
                    #if DEBUG
                    // the technical log, for development and UI tests only
                    Text(mesh.log.joined(separator: "\n")).font(.caption2.monospaced()).foregroundColor(Tema.ikincil)
                        .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("log")
                    #endif
                }
                .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 40)
            }
            .scrollDismissesKeyboard(.interactively)
            if let kalan = geriSayim { geriSayimKatmani(kalan).transition(.opacity) }
        }
        .animation(azHareket ? nil : .easeOut(duration: 0.18), value: geriSayim == nil)
        .preferredColorScheme(.light)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true; mesh.konumIste() } // the screen must not go dark under rubble
        // back after a while: nothing left selected from before (a short switch keeps a half-written call).
        // The one-argument form: the app supports iOS 16.
        .onChange(of: faz) { yeni in
            if yeni == .background { arkaPlan = Date() }
            if yeni == .active, let a = arkaPlan, Date().timeIntervalSince(a) > 120, geriSayim == nil { formuTemizle() }
        }
        .onReceive(sayac) { _ in
            guard let k = geriSayim else { return }
            if k <= 1 { gonder() } else {
                withAnimation(yay) { geriSayim = k - 1 }
                UIAccessibility.post(notification: .announcement, argument: "\(k - 1)")
            }
        }
    }

    // MARK: parts

    private var baslik: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                // the signal mark: a red dot in a tinted disc
                Circle().fill(Tema.kirmiziZemin).frame(width: 30, height: 30)
                    .overlay(Circle().fill(Tema.kirmizi).frame(width: 12, height: 12))
                    .accessibilityHidden(true)
                Text("KairoMesh").font(.system(size: 30, weight: .bold)).tracking(-0.3).foregroundColor(Tema.metin)
                    .accessibilityAddTraits(.isHeader)
            }
            Text(mesh.kurumVar ? "Çağrılar şu kuruma gider: \(mesh.kurumAd)" : "Bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm).")
                .font(.subheadline).foregroundColor(Tema.ikincil)
            // the network as a tinted capsule: the colour of the text is the colour of the pill
            Text(agYazisi).font(.subheadline.weight(.semibold)).foregroundColor(agRengi)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(agRengi.opacity(0.11)))
                .padding(.top, 2)
        }
    }

    private var agRengi: Color {
        mesh.bluetoothKapali && !mesh.internet ? Tema.kirmizi : mesh.internet || mesh.yakin > 0 ? Tema.yesil : Tema.amber
    }

    private var agYazisi: String {
        if mesh.internet { return "● İnternet var · çağrılar doğrudan kuruma gider" }
        if mesh.bluetoothKapali { return "● Bluetooth kapalı ya da izin verilmemiş · Ayarlar'dan açın; yoksa çağrınız başka telefonlara ulaşamaz" }
        if mesh.yakin > 0 { return "● İnternet yok · yakında çağrınızı taşıyabilecek \(mesh.yakin) telefon var" }
        return "● İnternet yok · yakında KairoMesh'li telefon yok; biri yaklaşınca çağrınız gider"
    }

    // 1. what happened: four life-threatening situations, large
    private var neOldu: some View {
        kart {
            adim(1, "Ne oldu?")
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                ForEach(Cagri.hayati, id: \.self) { k in durumDugmesi(k) }
            }
            HStack {
                Text(tur.map { "Seçili: \(Cagri.ad($0))" } ?? "Durum seçilmedi").font(.system(size: 17, weight: .semibold))
                    .foregroundColor(tur == nil ? Tema.ikincil : Tema.kirmizi).accessibilityIdentifier("secim")
                Spacer()
                if tur != nil {
                    Button { secimDegistir(nil) } label: {
                        Text("Kaldır").font(.headline).foregroundColor(Tema.kirmizi)
                            .padding(.horizontal, 20).frame(minHeight: 44)
                            .background(Capsule().fill(Tema.kirmiziZemin))
                    }.buttonStyle(BasincStil()).accessibilityLabel("Seçimi kaldır")
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
            }
        }
    }

    // 2. how many, 3. who is with you
    private var kacKisi: some View {
        kart {
            adim(2, "Kaç kişisiniz?")
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text(kisi.map(String.init) ?? "?").font(.system(size: 34, weight: .bold)).foregroundColor(Tema.metin)
                    Text("? = bilinmiyor").font(.subheadline).foregroundColor(Tema.ikincil)
                }
                Spacer()
                // a connected −/+ stepper, no keyboard needed
                HStack(spacing: 0) {
                    Button { kisi = kisi.flatMap { $0 <= 1 ? nil : $0 - 1 }; Dokunus.sec() } label: {
                        Text("−").font(.system(size: 28, weight: .semibold)).foregroundColor(Tema.metin).frame(width: 72, height: 60)
                    }.buttonStyle(BasincStil()).accessibilityLabel("Bir azalt")
                    Rectangle().fill(Tema.cizgi).frame(width: 1, height: 30)
                    Button { kisi = min(99, (kisi ?? 0) + 1); Dokunus.sec() } label: {
                        Text("+").font(.system(size: 28, weight: .semibold)).foregroundColor(Tema.metin).frame(width: 72, height: 60)
                    }.buttonStyle(BasincStil()).accessibilityLabel("Bir artır")
                }
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tema.griDolgu))
            }
            adim(3, "Yanınızda").padding(.top, 10)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(Cagri.ozel, id: \.0) { item in
                    let secili = ozel.contains(item.0)
                    Button {
                        withAnimation(yay) { if secili { ozel.remove(item.0) } else { ozel.insert(item.0) } }
                        Dokunus.sec()
                    } label: {
                        Text((secili ? "✓ " : "") + item.1).font(.system(size: 16, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .foregroundColor(secili ? Tema.kirmizi : Tema.metin)
                            .background(Capsule().fill(secili ? Tema.kirmiziZemin : Tema.griDolgu))
                            .overlay(Capsule().stroke(secili ? Tema.kirmizi : .clear, lineWidth: 1.5))
                    }.buttonStyle(BasincStil()).accessibilityValue(secili ? "seçili" : "seçili değil")
                }
            }
        }
    }

    // 4. or write it
    private var yazKart: some View {
        kart {
            adim(4, "Ya da kısaca yazın")
            TextField("Adres ve durum: ör. Antakya Ürgenpaşa Mh., enkazdayız, 3 kişi", text: $metin, axis: .vertical)
                .font(.system(size: 17)).lineLimit(2...5).padding(.horizontal, 14).padding(.vertical, 12).foregroundColor(Tema.metin)
                .focused($yaziyor)
                // a filled field; focus draws a dark ring so the caller sees where the text goes
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tema.griDolgu))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(yaziyor ? Tema.metin : .clear, lineWidth: 2))
                .accessibilityIdentifier("metin")
            if !metin.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(tur.map { "“\(Cagri.ad($0))” seçimi de yazınızla birlikte gönderilecek. İstemiyorsanız seçimi kaldırın." }
                     ?? "Durum seçmediniz: telefon yazınızdan anlayacak ve çağrıyı acil olarak gönderecek.")
                    .font(.subheadline.bold()).foregroundColor(Tema.amber)
            }
        }
    }

    // exactly what will go, then send
    private var gonderKart: some View {
        kart {
            (Text("Gönderilecekler\n").bold() + Text(ozetYazisi))
                .font(.system(size: 16)).foregroundColor(Tema.metin).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tema.griDolgu))
            Button { Dokunus.onay(); gonderBas() } label: {
                Text("Çağrıyı gönder").font(.system(size: 22, weight: .bold)).foregroundColor(.white)
                    .frame(maxWidth: .infinity, minHeight: 72)
                    .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tema.kirmizi)
                        .shadow(color: Tema.kirmizi.opacity(0.35), radius: 10, y: 4)) // the one lifted control
            }.buttonStyle(BasincStil()).accessibilityIdentifier("acilGonder").padding(.top, 2)
            if !mesh.acilDurum.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text(mesh.acilDurum).font(.system(size: 18, weight: .semibold)).foregroundColor(Tema.metin).accessibilityIdentifier("acilDurum")
                    if mesh.oncekiVar {
                        // one tap undoes a correction or "I am safe" sent by mistake
                        Button { mesh.geriAl() } label: {
                            Text("Geri al · hâlâ yardım gerekiyor").kalinDugme(Tema.kirmizi, .white)
                        }.buttonStyle(BasincStil()).accessibilityIdentifier("geriAl")
                    }
                    if mesh.sonCagriId != nil && !mesh.kurumAldi {
                        Button {
                            mesh.duzelt() // one tap, no delay; a mistaken correction is undone with "Geri al"
                        } label: {
                            Text("Yanlışlıkla gönderdim · yardım gerekmiyor").kalinDugme(Tema.kart, Tema.metin, kenar: Tema.cizgi)
                        }.buttonStyle(BasincStil()).accessibilityIdentifier("duzelt")
                    }
                }.padding(.horizontal, 16).padding(.vertical, 14).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(mesh.kurumAldi ? Tema.yesilZemin : Tema.kirmiziZemin))
                .transition(.opacity.combined(with: .offset(y: 8)))
            }
        }
        .animation(azHareket ? nil : .easeOut(duration: 0.26), value: mesh.acilDurum.isEmpty)
    }

    private func durumDugmesi(_ k: String) -> some View {
        let secili = tur == k
        return Button { secimDegistir(k) } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(Cagri.ad(k)).font(.system(size: 19, weight: .semibold))
                    .foregroundColor(secili ? .white : Tema.kirmizi).multilineTextAlignment(.leading)
                Text(secili ? "✓ Seçildi · kaldırmak için yine dokunun" : (Cagri.altYazi[k] ?? ""))
                    .font(.system(size: 14)).foregroundColor(secili ? .white.opacity(0.92) : Tema.ikincil)
            }
            // tinted when idle, filled and lifted when selected
            .frame(maxWidth: .infinity, minHeight: 104, alignment: .topLeading).padding(14)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(secili ? Tema.kirmizi : Tema.kirmiziZemin)
                .shadow(color: secili ? Tema.kirmizi.opacity(0.3) : .clear, radius: 8, y: 3))
        }
        .buttonStyle(BasincStil())
        .accessibilityIdentifier("tur_\(k)").accessibilityValue(secili ? "seçili" : "seçili değil")
    }

    private var ozetYazisi: String {
        let yazili = !metin.trimmingCharacters(in: .whitespaces).isEmpty
        var s = "• Durum: \(tur.map(Cagri.ad) ?? (yazili ? "yazdığınızdan anlaşılacak" : "—"))\n"
        s += "• Kişi sayısı: \(kisi.map(String.init) ?? "belirtilmedi")\n"
        s += "• Yanınızda: \(ozel.isEmpty ? "belirtilmedi" : ozel.sorted().map(Cagri.ozelAd).joined(separator: ", "))\n"
        if yazili { s += "• Mesaj: \"\(String(metin.trimmingCharacters(in: .whitespaces).prefix(80)))\"\n" }
        return s + "• Konum: izin verildiyse GPS eklenir"
    }

    private func geriSayimKatmani(_ kalan: Int) -> some View {
        ZStack {
            Tema.koyu.ignoresSafeArea() // opaque: nothing behind it can be tapped by mistake
            VStack(spacing: 0) {
                ZStack {
                    Circle().stroke(Tema.kirmiziAcik, lineWidth: 4)
                    Text("\(kalan)").font(.system(size: 76, weight: .bold)).foregroundColor(.white)
                        .id(kalan).transition(.asymmetric(insertion: .scale(scale: 1.2).combined(with: .opacity), removal: .opacity))
                }
                .frame(width: 168, height: 168).accessibilityHidden(true)
                Text("Çağrı \(kalan) saniye içinde\ngönderilecek").font(.system(size: 24, weight: .semibold)).foregroundColor(.white)
                    .multilineTextAlignment(.center).padding(.top, 24)
                Button { Dokunus.ret(); geriSayim = nil; mesh.iptalBildir() } label: {
                    Text("İptal et").font(.system(size: 30, weight: .bold)).foregroundColor(Tema.kirmizi)
                        .frame(maxWidth: .infinity, minHeight: 96)
                        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color.white))
                }.buttonStyle(BasincStil()).accessibilityIdentifier("iptal").padding(.top, 36)
                Button { gonder() } label: {
                    Text("Hemen gönder").font(.system(size: 20, weight: .semibold)).foregroundColor(.white)
                        .frame(maxWidth: .infinity, minHeight: 60)
                        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Color.white.opacity(0.6), lineWidth: 1.5))
                }.buttonStyle(BasincStil()).accessibilityIdentifier("hemenGonder").padding(.top, 14)
            }.padding(24)
        }
    }

    private func kart<C: View>(@ViewBuilder _ icerik: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12, content: icerik).padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Tema.kart))
    }

    /// A numbered step: a black disc and the heading.
    private func adim(_ no: Int, _ t: String) -> some View {
        HStack(spacing: 10) {
            Text("\(no)").font(.system(size: 14, weight: .bold)).foregroundColor(.white)
                .frame(width: 26, height: 26).background(Circle().fill(Tema.metin)).accessibilityHidden(true)
            Text(t).font(.system(size: 19, weight: .semibold)).foregroundColor(Tema.metin)
        }.accessibilityElement(children: .combine).accessibilityAddTraits(.isHeader)
    }

    // MARK: actions

    private func secimDegistir(_ k: String?) {
        // tapping the selected situation again clears it: a mistaken tap is undone where it happened
        withAnimation(yay) { tur = (k == nil || tur == k) ? nil : k }
        Dokunus.sec()
        UIAccessibility.post(notification: .announcement, argument: tur.map { "\(Cagri.ad($0)) seçildi" } ?? "Seçim kaldırıldı")
    }

    private func gonderBas() {
        yaziyor = false
        guard tur != nil || !metin.trimmingCharacters(in: .whitespaces).isEmpty else { return mesh.durumYaz("Önce durumunuzu seçin ya da kısaca yazın.") }
        guard mesh.kurumVar else { return mesh.kurumYok() }
        mesh.konumIste()
        if tur == "guvende" { return gonder() } // "I am safe" has no countdown: nothing urgent can go out by mistake
        geriSayim = 5 // a stray tap must not send a call: five seconds with a large CANCEL
    }

    private func gonder() {
        geriSayim = nil
        mesh.emergency(tur: tur, kisi: kisi, ozel: ozel, metin: metin)
        formuTemizle() // what went out is in the status card; the next call starts from nothing selected
        // once the call has gone the battery matters more: two minutes later the screen may sleep again (the sound
        // signal keeps it on while it plays)
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) { if !Siren.shared.caliyor { UIApplication.shared.isIdleTimerDisabled = false } }
    }

    private func formuTemizle() { tur = nil; kisi = nil; ozel = []; metin = "" }

    private func sinyalDegistir() {
        sinyal.toggle()
        if sinyal { Siren.shared.baslat(); UIApplication.shared.isIdleTimerDisabled = true } else { Siren.shared.durdur() }
    }
}

/// A loud repeating tone so rescuers can find a person they cannot see. Plays through the speaker even when the
/// ringer is silent.
final class Siren {
    static let shared = Siren()
    private var oynatici: AVAudioPlayer?
    var caliyor: Bool { oynatici?.isPlaying ?? false }

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

/// Apple's grouped light surfaces (same values as Android's MainActivity): a light grey ground, white cards without
/// frames, near-black text (secondary text 9:1, readable on a cracked screen), one accent: red.
enum Tema {
    static let zeminUst = Color(red: 242 / 255, green: 242 / 255, blue: 247 / 255)
    static let zeminAlt = Color(red: 228 / 255, green: 228 / 255, blue: 234 / 255)
    static let kart = Color.white
    static let cizgi = Color(red: 199 / 255, green: 199 / 255, blue: 204 / 255)
    static let griDolgu = Color(red: 239 / 255, green: 239 / 255, blue: 244 / 255)
    static let metin = Color(red: 28 / 255, green: 28 / 255, blue: 30 / 255)
    static let ikincil = Color(red: 72 / 255, green: 72 / 255, blue: 74 / 255)
    static let koyu = Color(red: 14 / 255, green: 26 / 255, blue: 34 / 255)
    static let kirmizi = Color(red: 179 / 255, green: 38 / 255, blue: 30 / 255)
    static let kirmiziAcik = Color(red: 255 / 255, green: 105 / 255, blue: 97 / 255)
    static let kirmiziZemin = Color(red: 253 / 255, green: 236 / 255, blue: 234 / 255)
    static let yesil = Color(red: 11 / 255, green: 110 / 255, blue: 79 / 255)
    static let yesilZemin = Color(red: 221 / 255, green: 240 / 255, blue: 231 / 255)
    static let amber = Color(red: 138 / 255, green: 90 / 255, blue: 0)
}

/// Press feedback: shrinks to 0.96 at once and springs back with a slight overshoot; no motion under Reduce Motion.
private struct BasincStil: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var azHareket
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !azHareket ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(azHareket ? nil : configuration.isPressed ? .easeOut(duration: 0.08) : .spring(response: 0.32, dampingFraction: 0.55),
                       value: configuration.isPressed)
    }
}

/// Haptics only on meaningful actions: a choice, sending, cancelling.
enum Dokunus {
    static func sec() { UISelectionFeedbackGenerator().selectionChanged() }
    static func onay() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func ret() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}

private extension Text {
    func kalinDugme(_ zemin: Color, _ yazi: Color, kenar: Color? = nil, boy: CGFloat = 56) -> some View {
        self.font(.system(size: 17, weight: .semibold)).foregroundColor(yazi).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: boy).padding(.horizontal, 12)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(zemin))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(kenar ?? zemin, lineWidth: 1))
    }
}
