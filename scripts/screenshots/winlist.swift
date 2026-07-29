import CoreGraphics
import Foundation

let opts = CGWindowListOption([.optionOnScreenOnly, .excludeDesktopElements])
guard let list = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] else {
    print("NO_WINDOW_LIST"); exit(1)
}
for w in list {
    let owner = w[kCGWindowOwnerName as String] as? String ?? ""
    let name = w[kCGWindowName as String] as? String ?? ""
    let id = w[kCGWindowNumber as String] as? Int ?? 0
    let b = w[kCGWindowBounds as String] as? [String: Any] ?? [:]
    let wd = b["Width"] as? Int ?? 0
    let ht = b["Height"] as? Int ?? 0
    // Only meaningfully sized windows
    if wd > 400 && ht > 300 {
        print("\(id)\t\(owner)\t\(wd)x\(ht)\t\(name)")
    }
}
