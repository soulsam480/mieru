package main

import (
	"log"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"

	"mieru-desktop/internal/store"
)

func main() {
	app, err := NewApp(store.Dir())
	if err != nil {
		log.Fatal(err)
	}
	mygo.App.WhenReady(func() {
		app.win = mygo.NewWindow(mygo.WindowOptions{
			Title:     "Mieru",
			Width:     1280,
			Height:    840,
			MinWidth:  960,
			MinHeight: 620,
			StateKey:  "main",
			Content:   ui.View(app.view),
		})
	})
	if err := mygo.App.Run(); err != nil {
		log.Fatal(err)
	}
}
