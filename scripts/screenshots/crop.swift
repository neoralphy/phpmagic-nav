// crop.swift <in.png> <out.png> <x> <y> <w> <h>  (pixel coords in the source image)
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let a = CommandLine.arguments
guard a.count == 7,
      let x = Int(a[3]), let y = Int(a[4]), let w = Int(a[5]), let h = Int(a[6]),
      let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: a[1]) as CFURL, nil),
      let img = CGImageSourceCreateImageAtIndex(src, 0, nil),
      let cropped = img.cropping(to: CGRect(x: x, y: y, width: w, height: h)) else {
    FileHandle.standardError.write("usage/crop failure\n".data(using: .utf8)!)
    exit(1)
}
guard let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: a[2]) as CFURL,
                                                 UTType.png.identifier as CFString, 1, nil) else { exit(1) }
CGImageDestinationAddImage(dest, cropped, nil)
exit(CGImageDestinationFinalize(dest) ? 0 : 1)
