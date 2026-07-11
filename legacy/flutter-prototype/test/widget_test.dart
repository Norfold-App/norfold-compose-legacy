import 'package:flutter_test/flutter_test.dart';
import 'package:libre_notes/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('Libre Notes opens to the main workspace', (tester) async {
    SharedPreferences.setMockInitialValues({});

    await tester.pumpWidget(const LibreNotesApp());
    await tester.pumpAndSettle();

    expect(find.text('Project Roadmap'), findsWidgets);
    expect(find.text('Edit'), findsWidgets);
    expect(find.text('Preview'), findsWidgets);
  });
}
